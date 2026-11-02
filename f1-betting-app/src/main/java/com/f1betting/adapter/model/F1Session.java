package com.f1betting.adapter.model;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * Provider-agnostic representation of an F1 session (event).
 * Mapped from whatever external API is in use.
 */
@Data
@Builder
public class F1Session {
    private String sessionKey;
    private String sessionName;
    private String sessionType;
    private Integer year;
    private String countryName;
    private String circuitName;
    private String location;
    private OffsetDateTime dateStart;
    private OffsetDateTime dateEnd;
}
