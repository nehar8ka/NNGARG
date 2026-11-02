package com.f1betting.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Request body for placing a bet on a driver to win an F1 event.
 */
@Data
public class PlaceBetRequest {

    /**
     * The ID of the registered user placing the bet.
     * Passed as a parameter since users are pre-registered.
     */
    @NotNull(message = "userId is required")
    private Long userId;

    /**
     * The session_key of the F1 event to bet on.
     * Obtain this from GET /api/v1/events.
     */
    @NotBlank(message = "sessionKey is required")
    private String sessionKey;

    /**
     * The official car number of the driver the user is backing to win.
     * Obtain valid driver numbers from GET /api/v1/events.
     */
    @NotNull(message = "driverNumber is required")
    private Integer driverNumber;

    /**
     * Amount in EUR to stake. Must be at least 0.01 EUR.
     * Immediately deducted from the user's balance on placement.
     */
    @NotNull(message = "stakeAmount is required")
    @DecimalMin(value = "0.01", message = "stakeAmount must be at least 0.01 EUR")
    private BigDecimal stakeAmount;
}
