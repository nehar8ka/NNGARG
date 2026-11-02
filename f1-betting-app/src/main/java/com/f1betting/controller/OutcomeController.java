package com.f1betting.controller;

import com.f1betting.dto.OutcomeResultDto;
import com.f1betting.dto.RecordOutcomeRequest;
import com.f1betting.service.OutcomeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Handles recording of F1 event outcomes and bet settlement.
 */
@RestController
@RequestMapping("/api/v1/events")
@RequiredArgsConstructor
public class OutcomeController {

    private final OutcomeService outcomeService;

    /**
     * Records the official outcome of a completed F1 event and settles all bets.
     *
     * <p>Once an outcome is recorded:
     * <ul>
     *   <li>All PENDING bets for the session are evaluated (WON or LOST).</li>
     *   <li>Winners receive their payout (stake × odds) credited to their balance.</li>
     *   <li>No further bets are accepted for this session.</li>
     * </ul>
     *
     * <p>Returns 422 if an outcome has already been recorded for this session.
     *
     * <p>Example:
     * <pre>
     *   POST /api/v1/events/9161/outcome
     *   { "winnerDriverNumber": 1 }
     * </pre>
     *
     * @param sessionKey the OpenF1 session_key of the completed event
     * @param request    the outcome details (winner's driver number)
     */
    @PostMapping("/{sessionKey}/outcome")
    public ResponseEntity<OutcomeResultDto> recordOutcome(
            @PathVariable String sessionKey,
            @Valid @RequestBody RecordOutcomeRequest request) {
        return ResponseEntity.ok(
                outcomeService.recordOutcome(sessionKey, request.getWinnerDriverNumber()));
    }
}
