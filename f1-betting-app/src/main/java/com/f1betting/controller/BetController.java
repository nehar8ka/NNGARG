package com.f1betting.controller;

import com.f1betting.dto.BetDto;
import com.f1betting.dto.PlaceBetRequest;
import com.f1betting.service.BettingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Handles bet placement and retrieval.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class BetController {

    private final BettingService bettingService;

    /**
     * Places a single bet on a driver to win an F1 event.
     *
     * <p>The stake is deducted from the user's balance immediately.
     * Returns 422 if: the event already has a result, the driver is not in the session,
     * or the user has insufficient funds.
     *
     * <p>Example request body:
     * <pre>{@code
     * {
     *   "userId": 1,
     *   "sessionKey": "9161",
     *   "driverNumber": 1,
     *   "stakeAmount": 20.00
     * }
     * }</pre>
     */
    @PostMapping("/bets")
    public ResponseEntity<BetDto> placeBet(@Valid @RequestBody PlaceBetRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(bettingService.placeBet(request));
    }

    /**
     * Retrieves a specific bet by its ID.
     */
    @GetMapping("/bets/{id}")
    public ResponseEntity<BetDto> getBet(@PathVariable Long id) {
        return ResponseEntity.ok(bettingService.getBetById(id));
    }

    /**
     * Lists all bets placed by a specific user.
     */
    @GetMapping("/users/{userId}/bets")
    public ResponseEntity<List<BetDto>> getUserBets(@PathVariable Long userId) {
        return ResponseEntity.ok(bettingService.getBetsByUser(userId));
    }
}
