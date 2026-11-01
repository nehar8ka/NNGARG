package com.f1betting.adapter.openf1;

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
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;

import java.util.Collections;
import java.util.List;

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
    private OpenF1RateLimiter rateLimiter = new OpenF1RateLimiter(10, 50, 60);

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
                .thenThrow(new HttpClientErrorException(HttpStatus.TOO_MANY_REQUESTS, "429 Too Many Requests"));

        List<F1Driver> drivers = provider.getDriversForSession("9081");
        assertNotNull(drivers);
        assertFalse(drivers.isEmpty());
        assertTrue(rateLimiter.isCooldownActive(), "Rate limiter cooldown should be activated on 429");
    }
}
