package com.f1betting.controller;

import com.f1betting.dto.EventDto;
import com.f1betting.service.EventService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Exposes F1 events (sessions) with driver markets.
 *
 * <p>Data is fetched live from the configured F1 data provider (OpenF1 by default).
 */
@RestController
@RequestMapping("/api/v1/events")
@RequiredArgsConstructor
public class EventController {

    private final EventService eventService;

    /**
     * Lists F1 events (sessions) from the external data provider.
     * All filter parameters are optional and combinable.
     *
     * <p>Each event in the response includes a {@code driverMarket} array with
     * randomly generated odds (2, 3, or 4) for each driver.
     *
     * @param sessionType filter by session type (e.g. "Race", "Qualifying", "Practice")
     * @param year        filter by season year (e.g. 2023, 2024)
     * @param country     filter by country name (e.g. "Italy", "United Kingdom")
     *
     * @return list of matching events with driver market
     *
     * <p>Example requests:
     * <pre>
     *   GET /api/v1/events
     *   GET /api/v1/events?sessionType=Race&year=2023
     *   GET /api/v1/events?country=Italy
     *   GET /api/v1/events?sessionType=Race&year=2024&country=Monaco
     * </pre>
     */
    @GetMapping
    public ResponseEntity<List<EventDto>> listEvents(
            @RequestParam(required = false) String sessionType,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) String country) {
        return ResponseEntity.ok(eventService.getEvents(sessionType, year, country));
    }
}
