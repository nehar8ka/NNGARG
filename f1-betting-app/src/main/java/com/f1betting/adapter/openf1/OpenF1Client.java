package com.f1betting.adapter.openf1;

import com.f1betting.adapter.openf1.dto.OpenF1DriverDto;
import com.f1betting.adapter.openf1.dto.OpenF1SessionDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Low-level HTTP client for the OpenF1 REST API.
 * Responsible only for making HTTP calls and returning raw DTOs.
 * Business mapping, rate limiting, and caching are handled by {@link OpenF1EventProvider}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OpenF1Client {

    private final RestTemplate restTemplate;

    @Value("${openf1.api.base-url}")
    private String baseUrl;

    private HttpHeaders createHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        headers.set("Accept", "application/json");
        return headers;
    }

    /**
     * Calls GET /sessions with optional query parameters.
     *
     * @param sessionType optional session type filter
     * @param year        optional year filter
     * @param country     optional country name filter
     * @return list of raw session DTOs
     */
    public List<OpenF1SessionDto> fetchSessions(String sessionType, Integer year, String country) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(baseUrl + "/sessions");

        if (sessionType != null && !sessionType.isBlank()) {
            builder.queryParam("session_type", sessionType);
        }
        if (year != null) {
            builder.queryParam("year", year);
        }
        if (country != null && !country.isBlank()) {
            builder.queryParam("country_name", country);
        }

        String url = builder.toUriString();
        log.info("Fetching F1 sessions: {}", url);

        try {
            HttpEntity<Void> requestEntity = new HttpEntity<>(createHeaders());
            ResponseEntity<OpenF1SessionDto[]> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    requestEntity,
                    OpenF1SessionDto[].class
            );

            OpenF1SessionDto[] body = response.getBody();
            return body != null ? Arrays.asList(body) : Collections.emptyList();
        } catch (HttpClientErrorException ex) {
            if (ex.getStatusCode() == HttpStatus.NOT_FOUND) {
                log.debug("OpenF1 returned 404 for sessions query: {}", url);
                return Collections.emptyList();
            }
            throw ex;
        }
    }

    /**
     * Calls GET /drivers?session_key={sessionKey}.
     *
     * @param sessionKey the OpenF1 session_key (or "latest")
     * @return list of raw driver DTOs
     */
    public List<OpenF1DriverDto> fetchDrivers(String sessionKey) {
        String url = UriComponentsBuilder.fromHttpUrl(baseUrl + "/drivers")
                .queryParam("session_key", sessionKey)
                .toUriString();

        log.debug("Fetching F1 drivers for session {}: {}", sessionKey, url);

        try {
            HttpEntity<Void> requestEntity = new HttpEntity<>(createHeaders());
            ResponseEntity<OpenF1DriverDto[]> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    requestEntity,
                    OpenF1DriverDto[].class
            );

            OpenF1DriverDto[] body = response.getBody();
            return body != null ? Arrays.asList(body) : Collections.emptyList();
        } catch (HttpClientErrorException ex) {
            if (ex.getStatusCode() == HttpStatus.NOT_FOUND) {
                log.debug("OpenF1 returned 404 Not Found for session {} drivers (session may be cancelled or no data available)", sessionKey);
                return Collections.emptyList();
            }
            throw ex;
        }
    }

    /**
     * Calls GET /drivers?session_key=latest to retrieve the latest driver grid.
     *
     * @return list of latest raw driver DTOs
     */
    public List<OpenF1DriverDto> fetchLatestDrivers() {
        return fetchDrivers("latest");
    }
}
