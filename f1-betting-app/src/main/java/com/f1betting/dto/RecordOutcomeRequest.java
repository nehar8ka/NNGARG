package com.f1betting.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body for recording the official outcome of a completed F1 event.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecordOutcomeRequest {

    /**
     * The official car number of the driver who won the event.
     */
    @NotNull(message = "winnerDriverNumber is required")
    @Min(value = 1, message = "winnerDriverNumber must be a positive driver number")
    private Integer winnerDriverNumber;
}
