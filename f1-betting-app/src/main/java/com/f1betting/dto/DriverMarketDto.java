package com.f1betting.dto;

import lombok.Builder;
import lombok.Data;

/**
 * A single driver entry in an event's driver market, including generated odds.
 */
@Data
@Builder
public class DriverMarketDto {

    /** The driver's official car number (also serves as their ID). */
    private Integer driverNumber;

    /** Driver's full name as provided by the data provider. */
    private String fullName;

    /** Three-letter name abbreviation (e.g. "VER", "HAM"). */
    private String nameAcronym;

    /** Constructor/team name. */
    private String teamName;

    /**
     * Randomly generated decimal odds for this driver to WIN the session.
     * Will be 2, 3, or 4.
     */
    private Integer odds;
}
