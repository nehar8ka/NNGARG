package com.f1betting.adapter.openf1;

import com.f1betting.adapter.F1EventProvider;
import com.f1betting.adapter.mock.MockF1EventProvider;
import com.f1betting.adapter.model.F1Driver;
import com.f1betting.adapter.model.F1Session;
import com.f1betting.adapter.model.SessionFilter;
import com.f1betting.adapter.openf1.dto.OpenF1DriverDto;
import com.f1betting.adapter.openf1.dto.OpenF1SessionDto;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * {@link F1EventProvider} implementation backed by the OpenF1 public API
 * (https://api.openf1.org/v1).
 *
 * <p>Includes:
 * <ul>
 *   <li>In-memory TTL caching for sessions and driver grids</li>
 *   <li>Negative caching for sessions with 404 / no telemetry (e.g. cancelled events)</li>
 *   <li>Proactive client-side rate limiting (token bucket / sliding window) to prevent HTTP 429</li>
 *   <li>Automatic HTTP 429 backoff / cooldown honoring Retry-After headers</li>
 *   <li>Transparent fallback to cached baseline grid or mock F1 data when offline or rate-limited</li>
 * </ul>
 */
@Component
@Primary
@RequiredArgsConstructor
@Slf4j
public class OpenF1EventProvider implements F1EventProvider {

    private final OpenF1Client client;
    private final MockF1EventProvider mockProvider;
    private final OpenF1RateLimiter rateLimiter;

    @Value("${openf1.cache.session-ttl-minutes:15}")
    private long sessionTtlMinutes = 15;

    @Value("${openf1.cache.driver-ttl-minutes:60}")
    private long driverTtlMinutes = 60;

    @Value("${openf1.cache.negative-ttl-minutes:15}")
    private long negativeTtlMinutes = 15;

    private final Map<String, CacheEntry<List<F1Session>>> sessionCache = new ConcurrentHashMap<>();
    private final Map<String, CacheEntry<List<F1Driver>>> driverCache = new ConcurrentHashMap<>();
    private volatile List<F1Driver> lastKnownGrid = null;

    @Override
    public List<F1Session> getSessions(SessionFilter filter) {
        String cacheKey = buildSessionCacheKey(filter);

        // 1. Check in-memory session cache
        CacheEntry<List<F1Session>> cached = sessionCache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            log.debug("Returning {} cached sessions for filter '{}'", cached.getData().size(), cacheKey);
            return cached.getData();
        }

        // 2. Check if rate limiter permits an outbound call
        if (!rateLimiter.tryAcquire()) {
            log.debug("OpenF1 rate limiter budget exhausted or cooldown active. Returning fallback sessions.");
            return mockProvider.getSessions(filter);
        }

        // 3. Acquire throttle permission (enforces >= 350ms spacing between calls)
        if (!rateLimiter.acquireWithThrottle()) {
            log.debug("OpenF1 rate limit throttle prevented request. Returning fallback sessions.");
            return mockProvider.getSessions(filter);
        }

        // 4. Attempt live fetch from OpenF1
        try {
            String sessionType = (filter != null) ? filter.getSessionType() : null;
            Integer year       = (filter != null) ? filter.getYear()        : null;
            String country     = (filter != null) ? filter.getCountry()     : null;

            List<OpenF1SessionDto> raw = client.fetchSessions(sessionType, year, country);
            log.info("OpenF1 returned {} sessions for filter: {}", raw.size(), filter);

            if (raw.isEmpty()) {
                log.warn("OpenF1 returned 0 sessions, checking fallback data...");
                return mockProvider.getSessions(filter);
            }

            List<F1Session> sessions = raw.stream()
                    .map(this::toF1Session)
                    .collect(Collectors.toList());

            sessionCache.put(cacheKey, new CacheEntry<>(sessions, sessionTtlMinutes * 60_000L));
            return sessions;
        } catch (HttpClientErrorException.TooManyRequests ex) {
            handleRateLimit429(ex, "sessions lookup");
            return mockProvider.getSessions(filter);
        } catch (Exception ex) {
            handleRateLimitOrFailure(ex, "sessions lookup");
            return mockProvider.getSessions(filter);
        }
    }

    @Override
    public List<F1Driver> getDriversForSession(String sessionKey) {
        if (sessionKey == null || sessionKey.isBlank()) {
            return getFallbackDrivers(sessionKey);
        }

        // 1. Check in-memory driver cache first (prevents redundant API calls)
        CacheEntry<List<F1Driver>> cached = driverCache.get(sessionKey);
        if (cached != null && !cached.isExpired() && !cached.getData().isEmpty()) {
            return cached.getData();
        }

        // 2. Pre-warm baseline grid lazily if not yet initialized
        ensureBaselineGrid();

        // 3. Proactively avoid hitting OpenF1 if rate limit budget is exhausted or cooldown is active
        if (!rateLimiter.tryAcquire()) {
            log.debug("OpenF1 rate limit reached or cooldown active. Using fallback grid for session {}", sessionKey);
            List<F1Driver> fallback = getFallbackDrivers(sessionKey);
            // Negative/fallback cache: remember this fallback so we do not retry this session repeatedly
            driverCache.put(sessionKey, new CacheEntry<>(fallback, negativeTtlMinutes * 60_000L));
            return fallback;
        }

        // 4. Acquire with throttle (guarantees >= 350ms between calls to respect 3 req/s)
        if (!rateLimiter.acquireWithThrottle()) {
            List<F1Driver> fallback = getFallbackDrivers(sessionKey);
            driverCache.put(sessionKey, new CacheEntry<>(fallback, negativeTtlMinutes * 60_000L));
            return fallback;
        }

        // 5. Attempt live fetch from OpenF1
        try {
            List<OpenF1DriverDto> raw = client.fetchDrivers(sessionKey);

            if (raw.isEmpty()) {
                // E.g. session was cancelled or no drivers registered in OpenF1 (Imola 2023, etc.)
                log.debug("OpenF1 returned 0 drivers for session {}. Caching fallback grid.", sessionKey);
                List<F1Driver> fallback = getFallbackDrivers(sessionKey);
                driverCache.put(sessionKey, new CacheEntry<>(fallback, negativeTtlMinutes * 60_000L));
                return fallback;
            }

            List<F1Driver> drivers = raw.stream()
                    .map(this::toF1Driver)
                    .collect(Collectors.toList());

            log.info("OpenF1 returned {} drivers for session {}", drivers.size(), sessionKey);
            driverCache.put(sessionKey, new CacheEntry<>(drivers, driverTtlMinutes * 60_000L));
            lastKnownGrid = drivers;
            return drivers;
        } catch (HttpClientErrorException.TooManyRequests ex) {
            handleRateLimit429(ex, "drivers lookup for session " + sessionKey);
            List<F1Driver> fallback = getFallbackDrivers(sessionKey);
            driverCache.put(sessionKey, new CacheEntry<>(fallback, negativeTtlMinutes * 60_000L));
            return fallback;
        } catch (Exception ex) {
            handleRateLimitOrFailure(ex, "drivers lookup for session " + sessionKey);
            List<F1Driver> fallback = getFallbackDrivers(sessionKey);
            driverCache.put(sessionKey, new CacheEntry<>(fallback, negativeTtlMinutes * 60_000L));
            return fallback;
        }
    }

    /**
     * Pre-warms the baseline grid with authentic F1 drivers using a single call to /drivers?session_key=latest.
     */
    private void ensureBaselineGrid() {
        if (lastKnownGrid != null && !lastKnownGrid.isEmpty()) {
            return;
        }
        if (!rateLimiter.tryAcquire()) {
            return;
        }
        try {
            if (rateLimiter.acquireWithThrottle()) {
                log.info("Fetching latest F1 grid from OpenF1 to establish baseline...");
                List<OpenF1DriverDto> latestRaw = client.fetchLatestDrivers();
                if (latestRaw != null && !latestRaw.isEmpty()) {
                    lastKnownGrid = latestRaw.stream()
                            .map(this::toF1Driver)
                            .collect(Collectors.toList());
                    log.info("Successfully established baseline grid with {} drivers from OpenF1.", lastKnownGrid.size());
                }
            }
        } catch (Exception e) {
            log.warn("Could not pre-warm baseline grid from OpenF1: {}", e.getMessage());
        }
    }

    private void handleRateLimit429(HttpClientErrorException.TooManyRequests ex, String operation) {
        Long retryAfterSeconds = null;
        try {
            HttpHeaders headers = ex.getResponseHeaders();
            if (headers != null) {
                String retryAfterStr = headers.getFirst("Retry-After");
                if (retryAfterStr != null && !retryAfterStr.isBlank()) {
                    retryAfterSeconds = Long.parseLong(retryAfterStr.trim());
                }
            }
        } catch (Exception ignored) {
        }
        rateLimiter.recordRateLimit429(retryAfterSeconds);
        log.warn("OpenF1 rate limit reached (429) during {}. Activating cooldown ({}s); using fallback data.",
                operation, (retryAfterSeconds != null ? retryAfterSeconds : 60));
    }

    private void handleRateLimitOrFailure(Exception ex, String operation) {
        String message = ex.getMessage() != null ? ex.getMessage() : "";
        if (message.contains("429") || message.toLowerCase().contains("too many requests")) {
            rateLimiter.recordRateLimit429(null);
            log.warn("OpenF1 rate limit detected during {}. Activating cooldown; using fallback data.", operation);
        } else {
            log.warn("OpenF1 {} failed ({}); falling back to built-in data.", operation, message);
        }
    }

    private List<F1Driver> getFallbackDrivers(String sessionKey) {
        if (lastKnownGrid != null && !lastKnownGrid.isEmpty()) {
            return lastKnownGrid;
        }
        return mockProvider.getDriversForSession(sessionKey);
    }

    private String buildSessionCacheKey(SessionFilter filter) {
        if (filter == null) {
            return "all";
        }
        return String.format("%s|%s|%s",
                filter.getSessionType() != null ? filter.getSessionType() : "*",
                filter.getYear() != null ? filter.getYear().toString() : "*",
                filter.getCountry() != null ? filter.getCountry() : "*");
    }

    /**
     * Clears all session and driver caches (useful for testing or cache management).
     */
    public void clearCaches() {
        sessionCache.clear();
        driverCache.clear();
        lastKnownGrid = null;
    }

    // -----------------------------------------------------------------------
    // Mapping helpers — kept private; only this adapter knows the DTO shape
    // -----------------------------------------------------------------------

    private F1Session toF1Session(OpenF1SessionDto dto) {
        return F1Session.builder()
                .sessionKey(dto.getSessionKey() != null ? dto.getSessionKey().toString() : null)
                .sessionName(dto.getSessionName())
                .sessionType(dto.getSessionType())
                .year(dto.getYear())
                .countryName(dto.getCountryName())
                .circuitName(dto.getCircuitShortName())
                .location(dto.getLocation())
                .dateStart(dto.getDateStart())
                .dateEnd(dto.getDateEnd())
                .build();
    }

    private F1Driver toF1Driver(OpenF1DriverDto dto) {
        return F1Driver.builder()
                .driverNumber(dto.getDriverNumber())
                .fullName(dto.getFullName())
                .nameAcronym(dto.getNameAcronym())
                .teamName(dto.getTeamName())
                .countryCode(dto.getCountryCode())
                .build();
    }

    // -----------------------------------------------------------------------
    // Internal Cache Entry
    // -----------------------------------------------------------------------

    @Getter
    private static class CacheEntry<T> {
        private final T data;
        private final long expiryTime;

        public CacheEntry(T data, long ttlMs) {
            this.data = data;
            this.expiryTime = System.currentTimeMillis() + ttlMs;
        }

        public boolean isExpired() {
            return System.currentTimeMillis() > expiryTime;
        }
    }
}
