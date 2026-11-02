package com.f1betting.adapter.model;

import lombok.Builder;
import lombok.Data;

/**
 * Filter criteria for querying F1 sessions from an external provider.
 * All fields are optional; null values are ignored by the provider.
 */
@Data
@Builder
public class SessionFilter {

    /**
     * Type of session to filter by (e.g. "Race", "Qualifying", "Practice").
     */
    private String sessionType;

    /** Filter sessions by year (e.g. 2023, 2024). */
    private Integer year;

    /** Filter sessions by country name (e.g. "Italy", "United Kingdom"). */
    private String country;
}
