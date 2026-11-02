package com.f1betting.adapter.openf1.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * Raw JSON response shape from the OpenF1 /drivers endpoint.
 * Mapped 1:1 with the API contract — do not use outside the adapter package.
 */
@Data
public class OpenF1DriverDto {

    @JsonProperty("driver_number")
    private Integer driverNumber;

    @JsonProperty("full_name")
    private String fullName;

    @JsonProperty("name_acronym")
    private String nameAcronym;

    @JsonProperty("team_name")
    private String teamName;

    @JsonProperty("country_code")
    private String countryCode;

    @JsonProperty("session_key")
    private Integer sessionKey;
}
