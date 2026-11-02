package com.f1betting.adapter.openf1;

import com.f1betting.adapter.openf1.exception.OpenF1RateLimitExceededException;

import com.f1betting.adapter.mock.MockF1EventProvider;
import com.f1betting.adapter.model.F1Driver;
import com.f1betting.adapter.model.F1Session;
import com.f1betting.adapter.model.SessionFilter;
import com.f1betting.adapter.openf1.dto.OpenF1DriverDto;
import com.f1betting.adapter.openf1.dto.OpenF1SessionDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OpenF1EventProviderTest {

    @Mock
    private OpenF1Client client;

    @Spy
    private MockF1EventProvider mockProvider = new MockF1EventProvider();

    @Spy
    private OpenF1RateLimiter rateLimiter = new OpenF1RateLimiter(10, 50, 60, 20);

    @InjectMocks
    private OpenF1EventProvider provider;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(provider, "sessionTtlMinutes", 15L);
        ReflectionTestUtils.setField(provider, "driverTtlMinutes", 60L);
        ReflectionTestUtils.setField(provider, "negativeTtlMinutes", 15L);
        provider.clearCaches();
    }

    @Test
    void testGetSessionsCachesResults() {
        OpenF1SessionDto sessionDto = new OpenF1SessionDto();
        sessionDto.setSessionKey(9161);
        sessionDto.setSessionName("Race");
        sessionDto.setSessionType("Race");
        sessionDto.setYear(2023);

        when(client.fetchSessions("Race", 2023, "Italy"))
                .thenReturn(List.of(sessionDto));

        SessionFilter filter = SessionFilter.builder()
                .sessionType("Race")
                .year(2023)
                .country("Italy")
                .build();

        // 1st call -> calls client
        List<F1Session> first = provider.getSessions(filter);
        assertEquals(1, first.size());
        assertEquals("9161", first.get(0).getSessionKey());
        verify(client, times(1)).fetchSessions("Race", 2023, "Italy");

        // 2nd call -> served from cache, client NOT called again
        List<F1Session> second = provider.getSessions(filter);
        assertEquals(1, second.size());
        verify(client, times(1)).fetchSessions("Race", 2023, "Italy");
    }

    @Test
    void testGetSessionsFiltersCancelledSessions() {
        OpenF1SessionDto activeSession = new OpenF1SessionDto();
        activeSession.setSessionKey(9157);
        activeSession.setSessionName("Race");
        activeSession.setIsCancelled(false);

        OpenF1SessionDto cancelledSession = new OpenF1SessionDto();
        cancelledSession.setSessionKey(9086);
        cancelledSession.setSessionName("Race (Cancelled)");
        cancelledSession.setIsCancelled(true);

        when(client.fetchSessions(any(), any(), any()))
                .thenReturn(List.of(activeSession, cancelledSession));

        List<F1Session> sessions = provider.getSessions(null);
        assertEquals(1, sessions.size());
        assertEquals("9157", sessions.get(0).getSessionKey());
    }

    @Test
    void testGetDriversForSessionCachesResults() {
        OpenF1DriverDto driverDto = new OpenF1DriverDto();
        driverDto.setDriverNumber(1);
        driverDto.setFullName("Max Verstappen");
        driverDto.setNameAcronym("VER");

        when(client.fetchDrivers("9161")).thenReturn(List.of(driverDto));

        // 1st call -> calls client
        List<F1Driver> first = provider.getDriversForSession("9161");
        assertEquals(1, first.size());
        assertEquals("Max Verstappen", first.get(0).getFullName());
        verify(client, times(1)).fetchDrivers("9161");

        // 2nd call -> served from cache
        List<F1Driver> second = provider.getDriversForSession("9161");
        assertEquals(1, second.size());
        verify(client, times(1)).fetchDrivers("9161");
    }

    @Test
    void testGetDriversNormalizesNullTeamAndDeduplicates() {
        OpenF1DriverDto driver1 = new OpenF1DriverDto();
        driver1.setDriverNumber(1);
        driver1.setFullName("Max Verstappen");
        driver1.setTeamName(null);

        OpenF1DriverDto driver1Dup = new OpenF1DriverDto();
        driver1Dup.setDriverNumber(1);
        driver1Dup.setFullName("Max Verstappen Duplicate");
        driver1Dup.setTeamName("Red Bull");

        OpenF1DriverDto driver50 = new OpenF1DriverDto();
        driver50.setDriverNumber(50);
        driver50.setFullName(null);
        driver50.setBroadcastName("R HIRAKAWA");
        driver50.setTeamName("");

        when(client.fetchDrivers("latest")).thenReturn(List.of(driver1, driver1Dup, driver50));

        List<F1Driver> drivers = provider.getDriversForSession("latest");
        assertEquals(2, drivers.size());

        F1Driver first = drivers.get(0);
        assertEquals(1, first.getDriverNumber());
        assertEquals("Unassigned", first.getTeamName());

        F1Driver second = drivers.get(1);
        assertEquals(50, second.getDriverNumber());
        assertEquals("R HIRAKAWA", second.getFullName());
        assertEquals("Unassigned", second.getTeamName());
    }

    @Test
    void testCacheBoundsPreventUnboundedGrowth() {
        OpenF1DriverDto driverDto = new OpenF1DriverDto();
        driverDto.setDriverNumber(1);
        driverDto.setFullName("Max Verstappen");
        when(client.fetchDrivers(anyString())).thenReturn(List.of(driverDto));

        // Insert 550 sessions (threshold is 500)
        for (int i = 0; i < 550; i++) {
            provider.getDriversForSession("sess_" + i);
        }

        Map<?, ?> driverCache = (Map<?, ?>) ReflectionTestUtils.getField(provider, "driverCache");
        assertNotNull(driverCache);
        assertTrue(driverCache.size() <= 500, "driverCache should be bounded to 500 max entries, actual: " + driverCache.size());
    }

    @Test
    void testNegativeCachingWhenSessionReturnsEmptyOr404() {
        // e.g. cancelled session 9079 returns empty list
        when(client.fetchDrivers("9079")).thenReturn(Collections.emptyList());

        // 1st call -> calls client, gets empty list, caches fallback
        List<F1Driver> first = provider.getDriversForSession("9079");
        assertNotNull(first);
        assertFalse(first.isEmpty(), "Should return fallback drivers");
        verify(client, times(1)).fetchDrivers("9079");

        // 2nd call -> served from negative cache, client NOT called again!
        List<F1Driver> second = provider.getDriversForSession("9079");
        assertNotNull(second);
        assertEquals(first.size(), second.size());
        verify(client, times(1)).fetchDrivers("9079");
    }

    @Test
    void testRateLimiterExhaustionFallsBackWithoutCallingClient() {
        // Mock rate limiter to reject request
        doReturn(false).when(rateLimiter).tryAcquire();

        List<F1Driver> drivers = provider.getDriversForSession("9999");
        assertNotNull(drivers);
        assertFalse(drivers.isEmpty());
        // Client should never be called when rate limiter budget is exhausted
        verify(client, never()).fetchDrivers("9999");
    }

    @Test
    void testHttp429ActivatesCooldownAndFallsBack() {
        when(client.fetchDrivers("9081"))
                .thenThrow(new OpenF1RateLimitExceededException("429 Too Many Requests", 60L));

        List<F1Driver> drivers = provider.getDriversForSession("9081");
        assertNotNull(drivers);
        assertFalse(drivers.isEmpty());
        assertTrue(rateLimiter.isCooldownActive(), "Rate limiter cooldown should be activated on 429");
    }

    @Test
    void testConcurrentEnsureBaselineGridOnlyCallsClientOnce() throws Exception {
        OpenF1DriverDto driverDto = new OpenF1DriverDto();
        driverDto.setDriverNumber(1);
        driverDto.setFullName("Max Verstappen");
        driverDto.setNameAcronym("VER");

        when(client.fetchLatestDrivers()).thenReturn(List.of(driverDto));
        when(client.fetchDrivers(anyString())).thenReturn(List.of(driverDto));

        int threadCount = 10;
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(threadCount);
        java.util.concurrent.CountDownLatch readyLatch = new java.util.concurrent.CountDownLatch(threadCount);
        java.util.concurrent.CountDownLatch startLatch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch doneLatch = new java.util.concurrent.CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final String sessionKey = "session_" + i;
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    provider.getDriversForSession(sessionKey);
                } catch (Exception ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await();
        startLatch.countDown();
        doneLatch.await(5, java.util.concurrent.TimeUnit.SECONDS);
        executor.shutdown();

        // Baseline grid should be fetched AT MOST once despite 10 concurrent threads racing
        verify(client, times(1)).fetchLatestDrivers();
    }

    @Test
    void testGetBaselineDriverGridReturnsLatestGrid() {
        OpenF1DriverDto driverDto = new OpenF1DriverDto();
        driverDto.setDriverNumber(44);
        driverDto.setFullName("Lewis Hamilton");
        driverDto.setNameAcronym("HAM");

        when(client.fetchLatestDrivers()).thenReturn(List.of(driverDto));

        List<F1Driver> grid = provider.getBaselineDriverGrid();
        assertNotNull(grid);
        assertEquals(1, grid.size());
        assertEquals("Lewis Hamilton", grid.get(0).getFullName());
        verify(client, times(1)).fetchLatestDrivers();

        // 2nd call served from memory, no 2nd client call
        List<F1Driver> grid2 = provider.getBaselineDriverGrid();
        assertEquals(1, grid2.size());
        verify(client, times(1)).fetchLatestDrivers();
    }
}
