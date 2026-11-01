package com.f1betting.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Request body for recording the official outcome of a completed F1 event.
 */
@Data
public class RecordOutcomeRequest {

    /**
     * The official car number of the driver who won the event.
     */
    @NotNull(message = "winnerDriverNumber is required")
    private Integer winnerDriverNumber;
}
