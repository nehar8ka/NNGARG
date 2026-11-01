package com.f1betting.dto;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Full event (session) data returned to the client, including the driver market.
 */
@Data
@Builder
public class EventDto {

    /** Unique session identifier from the data provider. */
    private String sessionKey;

    /** Human-readable session name (e.g. "Race", "Sprint"). */
    private String sessionName;

    /** Session type (e.g. "Race", "Qualifying", "Practice"). */
    private String sessionType;

    private Integer year;
    private String countryName;
    private String circuitName;
    private String location;
    private OffsetDateTime dateStart;
    private OffsetDateTime dateEnd;

    /**
     * The driver market for this event — each driver with their current odds.
     */
    private List<DriverMarketDto> driverMarket;
}
