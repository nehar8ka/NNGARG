package com.f1betting.adapter.model;

import lombok.Builder;
import lombok.Data;

/**
 * Provider-agnostic representation of an F1 driver participating in a session.
 */
@Data
@Builder
public class F1Driver {
    private Integer driverNumber;
    private String fullName;
    private String teamName;
    private String countryCode;
    private String nameAcronym;
}
