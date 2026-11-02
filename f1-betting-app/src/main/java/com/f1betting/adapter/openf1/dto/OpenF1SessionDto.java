package com.f1betting.adapter.openf1.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * Raw JSON response shape from the OpenF1 /sessions endpoint.
 * Mapped 1:1 with the API contract — do not use outside the adapter package.
 */
@Data
public class OpenF1SessionDto {

    @JsonProperty("session_key")
    private Integer sessionKey;

    @JsonProperty("session_name")
    private String sessionName;

    @JsonProperty("session_type")
    private String sessionType;

    @JsonProperty("year")
    private Integer year;

    @JsonProperty("country_name")
    private String countryName;

    @JsonProperty("circuit_short_name")
    private String circuitShortName;

    @JsonProperty("location")
    private String location;

    @JsonProperty("date_start")
    private OffsetDateTime dateStart;

    @JsonProperty("date_end")
    private OffsetDateTime dateEnd;
}
