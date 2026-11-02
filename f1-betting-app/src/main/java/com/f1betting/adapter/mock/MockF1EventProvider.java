package com.f1betting.adapter.mock;

import com.f1betting.adapter.F1EventProvider;
import com.f1betting.adapter.model.F1Driver;
import com.f1betting.adapter.model.F1Session;
import com.f1betting.adapter.model.SessionFilter;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Fallback provider containing official F1 historical sessions and drivers.
 * Used when the live OpenF1 API is offline, unreachable, or DNS-blocked.
 */
@Component
public class MockF1EventProvider implements F1EventProvider {

    private final List<F1Session> mockSessions = new ArrayList<>();
    private final Map<String, List<F1Driver>> sessionDrivers = new HashMap<>();

    public MockF1EventProvider() {
        initData();
    }

    private void initData() {
        F1Session monza2023 = F1Session.builder()
                .sessionKey("9161")
                .sessionName("Race")
                .sessionType("Race")
                .year(2023)
                .countryName("Italy")
                .circuitName("Monza")
                .location("Monza")
                .dateStart(OffsetDateTime.parse("2023-09-03T13:00:00Z"))
                .dateEnd(OffsetDateTime.parse("2023-09-03T15:00:00Z"))
                .build();

        F1Session monaco2024 = F1Session.builder()
                .sessionKey("9520")
                .sessionName("Race")
                .sessionType("Race")
                .year(2024)
                .countryName("Monaco")
                .circuitName("Monte Carlo")
                .location("Monaco")
                .dateStart(OffsetDateTime.parse("2024-05-26T13:00:00Z"))
                .dateEnd(OffsetDateTime.parse("2024-05-26T15:00:00Z"))
                .build();

        F1Session silverstone2024 = F1Session.builder()
                .sessionKey("9540")
                .sessionName("Race")
                .sessionType("Race")
                .year(2024)
                .countryName("United Kingdom")
                .circuitName("Silverstone")
                .location("Silverstone")
                .dateStart(OffsetDateTime.parse("2024-07-07T14:00:00Z"))
                .dateEnd(OffsetDateTime.parse("2024-07-07T16:00:00Z"))
                .build();

        F1Session bahrain2024 = F1Session.builder()
                .sessionKey("9472")
                .sessionName("Race")
                .sessionType("Race")
                .year(2024)
                .countryName("Bahrain")
                .circuitName("Sakhir")
                .location("Sakhir")
                .dateStart(OffsetDateTime.parse("2024-03-02T15:00:00Z"))
                .dateEnd(OffsetDateTime.parse("2024-03-02T17:00:00Z"))
                .build();

        mockSessions.addAll(List.of(monza2023, monaco2024, silverstone2024, bahrain2024));

        List<F1Driver> standardGrid = List.of(
                F1Driver.builder().driverNumber(1).fullName("Max VERSTAPPEN").teamName("Red Bull Racing").nameAcronym("VER").countryCode("NED").build(),
                F1Driver.builder().driverNumber(44).fullName("Lewis HAMILTON").teamName("Mercedes").nameAcronym("HAM").countryCode("GBR").build(),
                F1Driver.builder().driverNumber(16).fullName("Charles LECLERC").teamName("Ferrari").nameAcronym("LEC").countryCode("MON").build(),
                F1Driver.builder().driverNumber(4).fullName("Lando NORRIS").teamName("McLaren").nameAcronym("NOR").countryCode("GBR").build(),
                F1Driver.builder().driverNumber(55).fullName("Carlos SAINZ").teamName("Ferrari").nameAcronym("SAI").countryCode("ESP").build(),
                F1Driver.builder().driverNumber(63).fullName("George RUSSELL").teamName("Mercedes").nameAcronym("RUS").countryCode("GBR").build(),
                F1Driver.builder().driverNumber(81).fullName("Oscar PIASTRI").teamName("McLaren").nameAcronym("PIA").countryCode("AUS").build(),
                F1Driver.builder().driverNumber(14).fullName("Fernando ALONSO").teamName("Aston Martin").nameAcronym("ALO").countryCode("ESP").build(),
                F1Driver.builder().driverNumber(11).fullName("Sergio PEREZ").teamName("Red Bull Racing").nameAcronym("PER").countryCode("MEX").build(),
                F1Driver.builder().driverNumber(10).fullName("Pierre GASLY").teamName("Alpine").nameAcronym("GAS").countryCode("FRA").build()
        );

        for (F1Session session : mockSessions) {
            sessionDrivers.put(session.getSessionKey(), standardGrid);
        }
    }

    @Override
    public List<F1Session> getSessions(SessionFilter filter) {
        if (filter == null) {
            return new ArrayList<>(mockSessions);
        }
        return mockSessions.stream()
                .filter(s -> filter.getSessionType() == null || s.getSessionType().equalsIgnoreCase(filter.getSessionType()))
                .filter(s -> filter.getYear() == null || s.getYear().equals(filter.getYear()))
                .filter(s -> filter.getCountry() == null || s.getCountryName().toLowerCase().contains(filter.getCountry().toLowerCase()))
                .collect(Collectors.toList());
    }

    @Override
    public List<F1Driver> getDriversForSession(String sessionKey) {
        return sessionDrivers.getOrDefault(sessionKey, sessionDrivers.get("9161"));
    }

    @Override
    public List<F1Driver> getBaselineDriverGrid() {
        return getDriversForSession("9161");
    }
}
