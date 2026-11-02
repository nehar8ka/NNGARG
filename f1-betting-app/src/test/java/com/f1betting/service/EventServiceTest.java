package com.f1betting.service;

import com.f1betting.adapter.F1EventProvider;
import com.f1betting.adapter.model.F1Driver;
import com.f1betting.adapter.model.F1Session;
import com.f1betting.adapter.model.SessionFilter;
import com.f1betting.dto.EventDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EventServiceTest {

    @Mock
    private F1EventProvider f1EventProvider;

    @Spy
    private OddsGenerator oddsGenerator = new OddsGenerator();

    @InjectMocks
    private EventService eventService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(eventService, "bulkListingThreshold", 20);
    }

    @Test
    void testGetEventsBelowThresholdCallsPerSessionDrivers() {
        F1Session session1 = F1Session.builder().sessionKey("101").sessionName("FP1").build();
        F1Session session2 = F1Session.builder().sessionKey("102").sessionName("Race").build();
        F1Driver driver = F1Driver.builder().driverNumber(1).fullName("Max Verstappen").build();

        when(f1EventProvider.getSessions(any(SessionFilter.class))).thenReturn(List.of(session1, session2));
        when(f1EventProvider.getDriversForSession("101")).thenReturn(List.of(driver));
        when(f1EventProvider.getDriversForSession("102")).thenReturn(List.of(driver));

        List<EventDto> events = eventService.getEvents("Race", 2024, "Monaco");

        assertEquals(2, events.size());
        verify(f1EventProvider, times(1)).getDriversForSession("101");
        verify(f1EventProvider, times(1)).getDriversForSession("102");
        verify(f1EventProvider, never()).getBaselineDriverGrid();
    }

    @Test
    void testGetEventsAboveThresholdUsesSingleBaselineGrid() {
        List<F1Session> bulkSessions = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            bulkSessions.add(F1Session.builder().sessionKey("session_" + i).sessionName("Session " + i).build());
        }

        F1Driver baselineDriver = F1Driver.builder().driverNumber(44).fullName("Lewis Hamilton").build();

        when(f1EventProvider.getSessions(any())).thenReturn(bulkSessions);
        when(f1EventProvider.getBaselineDriverGrid()).thenReturn(List.of(baselineDriver));

        List<EventDto> events = eventService.getEvents(null, null, null);

        assertEquals(25, events.size());
        // Baseline grid must be called exactly once
        verify(f1EventProvider, times(1)).getBaselineDriverGrid();
        // Individual session driver calls must NEVER be made
        verify(f1EventProvider, never()).getDriversForSession(anyString());

        // Each event should have the baseline driver market
        for (EventDto event : events) {
            assertEquals(1, event.getDriverMarket().size());
            assertEquals("Lewis Hamilton", event.getDriverMarket().get(0).getFullName());
        }
    }
}
