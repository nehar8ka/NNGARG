package com.f1betting.service;

import com.f1betting.adapter.F1EventProvider;
import com.f1betting.adapter.model.F1Driver;
import com.f1betting.adapter.model.F1Session;
import com.f1betting.adapter.model.SessionFilter;
import com.f1betting.dto.DriverMarketDto;
import com.f1betting.dto.EventDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Random;
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

    /** Possible odds values per the requirements. */
    private static final int[] POSSIBLE_ODDS = {2, 3, 4};

    private final F1EventProvider f1EventProvider;
    private final Random random = new Random();

    /**
     * Retrieves F1 sessions from the external provider, applying optional filters,
     * and enriches each session with a driver market containing randomly generated odds.
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
                .odds(randomOdds())
                .build();
    }

    /**
     * Generates a random odds value of 2, 3, or 4 as per the requirements.
     */
    private int randomOdds() {
        return POSSIBLE_ODDS[random.nextInt(POSSIBLE_ODDS.length)];
    }
}
