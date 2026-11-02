package com.f1betting.service;

import com.f1betting.adapter.F1EventProvider;
import com.f1betting.adapter.model.F1Driver;
import com.f1betting.adapter.model.F1Session;
import com.f1betting.adapter.model.SessionFilter;
import com.f1betting.dto.DriverMarketDto;
import com.f1betting.dto.EventDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Business logic for querying F1 events and building the driver market.
 *
 * <p>Depends on {@link F1EventProvider} — not on any specific API implementation.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EventService {

    private final F1EventProvider f1EventProvider;
    private final OddsGenerator oddsGenerator;

    @Value("${events.bulk-listing-threshold:20}")
    private int bulkListingThreshold = 20;

    /**
     * Retrieves F1 sessions from the external provider, applying optional filters,
     * and enriches each session with a driver market containing randomly generated odds.
     *
     * <p>If the result size exceeds {@code bulkListingThreshold}, a single baseline
     * driver grid is fetched once and reused across all sessions to prevent N+1 API calls.
     * Otherwise, session-specific drivers are queried live.
     *
     * @param sessionType optional session type filter (e.g. "Race", "Qualifying")
     * @param year        optional year filter
     * @param country     optional country name filter
     * @return list of events with their driver markets
     */
    public List<EventDto> getEvents(String sessionType, Integer year, String country) {
        SessionFilter filter = SessionFilter.builder()
                .sessionType(sessionType)
                .year(year)
                .country(country)
                .build();

        List<F1Session> sessions = f1EventProvider.getSessions(filter);
        log.info("Building event DTOs for {} sessions.", sessions.size());

        if (sessions.size() > bulkListingThreshold) {
            log.info("Bulk session count ({} > threshold {}); using single baseline driver grid.",
                    sessions.size(), bulkListingThreshold);
            List<F1Driver> baselineGrid = f1EventProvider.getBaselineDriverGrid();
            return sessions.stream()
                    .map(session -> toEventDtoWithDrivers(session, baselineGrid))
                    .collect(Collectors.toList());
        }

        return sessions.stream()
                .map(this::toEventDto)
                .collect(Collectors.toList());
    }

    // -----------------------------------------------------------------------
    // Mapping
    // -----------------------------------------------------------------------

    private EventDto toEventDto(F1Session session) {
        List<F1Driver> drivers;
        try {
            drivers = f1EventProvider.getDriversForSession(session.getSessionKey());
        } catch (Exception ex) {
            log.warn("Could not fetch drivers for session {}: {}", session.getSessionKey(), ex.getMessage());
            drivers = List.of();
        }

        return toEventDtoWithDrivers(session, drivers);
    }

    private EventDto toEventDtoWithDrivers(F1Session session, List<F1Driver> drivers) {
        List<DriverMarketDto> market = drivers.stream()
                .map(this::toDriverMarketDto)
                .collect(Collectors.toList());

        return EventDto.builder()
                .sessionKey(session.getSessionKey())
                .sessionName(session.getSessionName())
                .sessionType(session.getSessionType())
                .year(session.getYear())
                .countryName(session.getCountryName())
                .circuitName(session.getCircuitName())
                .location(session.getLocation())
                .dateStart(session.getDateStart())
                .dateEnd(session.getDateEnd())
                .driverMarket(market)
                .build();
    }

    private DriverMarketDto toDriverMarketDto(F1Driver driver) {
        return DriverMarketDto.builder()
                .driverNumber(driver.getDriverNumber())
                .fullName(driver.getFullName())
                .nameAcronym(driver.getNameAcronym())
                .teamName(driver.getTeamName())
                .odds(oddsGenerator.generateOdds())
                .build();
    }
}
