package com.f1betting.adapter.openf1;

import com.f1betting.adapter.openf1.dto.OpenF1DriverDto;
import com.f1betting.adapter.openf1.dto.OpenF1SessionDto;
import com.f1betting.adapter.openf1.exception.OpenF1RateLimitExceededException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OpenF1ClientTest {

    @Mock
    private RestTemplate restTemplate;

    @InjectMocks
    private OpenF1Client client;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(client, "baseUrl", "https://api.openf1.org/v1");
    }

    @Test
    void testFetchDriversSuccess() {
        OpenF1DriverDto dto = new OpenF1DriverDto();
        dto.setDriverNumber(1);
        dto.setFullName("Max Verstappen");
        dto.setNameAcronym("VER");

        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(), eq(OpenF1DriverDto[].class)))
                .thenReturn(new ResponseEntity<>(new OpenF1DriverDto[]{dto}, HttpStatus.OK));

        List<OpenF1DriverDto> result = client.fetchDrivers("9161");
        assertEquals(1, result.size());
        assertEquals("Max Verstappen", result.get(0).getFullName());
    }

    @Test
    void testFetchDrivers404ReturnsEmptyList() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(), eq(OpenF1DriverDto[].class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.NOT_FOUND, "No results found."));

        List<OpenF1DriverDto> result = client.fetchDrivers("9079");
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void testFetchDrivers429ThrowsOpenF1RateLimitExceededExceptionWithRetryAfter() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Retry-After", "5");
        HttpClientErrorException tooManyRequests = HttpClientErrorException.create(
                HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests", headers, null, null);

        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(), eq(OpenF1DriverDto[].class)))
                .thenThrow(tooManyRequests);

        OpenF1RateLimitExceededException ex = assertThrows(
                OpenF1RateLimitExceededException.class,
                () -> client.fetchDrivers("9161")
        );
        assertEquals(5L, ex.getRetryAfterSeconds());
    }

    @Test
    void testFetchSessionsSuccess() {
        OpenF1SessionDto dto = new OpenF1SessionDto();
        dto.setSessionKey(9161);
        dto.setSessionName("Race");
        dto.setYear(2023);

        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(), eq(OpenF1SessionDto[].class)))
                .thenReturn(new ResponseEntity<>(new OpenF1SessionDto[]{dto}, HttpStatus.OK));

        List<OpenF1SessionDto> result = client.fetchSessions("Race", 2023, "Italy");
        assertEquals(1, result.size());
        assertEquals(9161, result.get(0).getSessionKey());
    }

    @Test
    void testFetchSessions404ReturnsEmptyList() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(), eq(OpenF1SessionDto[].class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.NOT_FOUND, "No results found."));

        List<OpenF1SessionDto> result = client.fetchSessions("Unknown", 1900, "Nowhere");
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }
}
