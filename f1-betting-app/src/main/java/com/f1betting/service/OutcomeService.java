package com.f1betting.service;

import com.f1betting.adapter.F1EventProvider;
import com.f1betting.adapter.model.F1Driver;
import com.f1betting.domain.Bet;
import com.f1betting.domain.EventOutcome;
import com.f1betting.domain.User;
import com.f1betting.dto.OutcomeResultDto;
import com.f1betting.enums.BetStatus;
import com.f1betting.exception.BettingException;
import com.f1betting.exception.ResourceNotFoundException;
import com.f1betting.repository.BetRepository;
import com.f1betting.repository.EventOutcomeRepository;
import com.f1betting.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Business logic for recording event outcomes and settling bets.
 *
 * <p>When an outcome is recorded for a session:
 * <ol>
 *   <li>The declared winner is validated against the actual session roster via the F1 provider.</li>
 *   <li>The outcome is persisted atomically (guarded by session-level locking and DB unique constraints).</li>
 *   <li>All PENDING bets for the session are settled (WON or LOST).</li>
 *   <li>Winning users have their payout (stake × odds) credited to their balance.</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OutcomeService {

    private final EventOutcomeRepository eventOutcomeRepository;
    private final BetRepository betRepository;
    private final UserRepository userRepository;
    private final F1EventProvider f1EventProvider;
    private final SessionLockManager sessionLockManager;
    private final TransactionTemplate transactionTemplate;
    private final ExecutorService f1ValidationExecutor;

    @Value("${betting.driver-validation-timeout-seconds:8}")
    private long driverValidationTimeoutSeconds = 8;

    /**
     * Records the official outcome for a completed F1 event and settles all
     * pending bets for that session.
     *
     * @param sessionKey          the session identifier
     * @param winnerDriverNumber  the car number of the race winner
     * @return settlement summary
     * @throws BettingException if winner driver did not race or outcome has already been recorded
     */
    public OutcomeResultDto recordOutcome(String sessionKey, Integer winnerDriverNumber) {
        if (sessionKey == null || sessionKey.isBlank()) {
            throw new BettingException("sessionKey is required.");
        }
        if (winnerDriverNumber == null || winnerDriverNumber <= 0) {
            throw new BettingException("winnerDriverNumber must be a positive driver number.");
        }

        // Fast-fail check before external network I/O
        if (eventOutcomeRepository.existsBySessionKey(sessionKey)) {
            throw new BettingException(
                    "An outcome has already been recorded for session '" + sessionKey + "'.");
        }

        // Validate that declared winner actually participated in the session (OUTSIDE DB transaction)
        validateWinnerInSession(sessionKey, winnerDriverNumber);

        // Coordinate with BettingService via fine-grained lock to eliminate race conditions
        return sessionLockManager.executeWithLock(sessionKey, () -> {
            try {
                return transactionTemplate.execute(status -> {
                    // Atomic check inside lock & transaction
                    if (eventOutcomeRepository.existsBySessionKey(sessionKey)) {
                        throw new BettingException(
                                "An outcome has already been recorded for session '" + sessionKey + "'.");
                    }

                    // Persist the outcome
                    EventOutcome outcome = EventOutcome.builder()
                            .sessionKey(sessionKey)
                            .winnerDriverNumber(winnerDriverNumber)
                            .recordedAt(LocalDateTime.now())
                            .build();
                    eventOutcomeRepository.save(outcome);
                    log.info("Outcome recorded for session {}: winner driver #{}", sessionKey, winnerDriverNumber);

                    // Fetch all pending bets for this session
                    List<Bet> pendingBets = betRepository.findBySessionKeyAndStatus(sessionKey, BetStatus.PENDING);
                    log.info("Settling {} pending bet(s) for session {}", pendingBets.size(), sessionKey);

                    // Settle each bet
                    List<OutcomeResultDto.BetSettlementDto> settlements = new ArrayList<>();
                    BigDecimal totalPaidOut = BigDecimal.ZERO;
                    int betsWon = 0;
                    int betsLost = 0;
                    LocalDateTime settledAt = LocalDateTime.now();

                    for (Bet bet : pendingBets) {
                        boolean won = bet.getDriverNumber().equals(winnerDriverNumber);

                        if (won) {
                            bet.setStatus(BetStatus.WON);

                            // Credit payout to the user's balance with pessimistic lock
                            User user = userRepository.findByIdForUpdate(bet.getUser().getId())
                                    .orElseThrow(() -> new ResourceNotFoundException(
                                            "User not found with id: " + bet.getUser().getId()));
                            user.credit(bet.getPotentialPayout());
                            userRepository.save(user);

                            totalPaidOut = totalPaidOut.add(bet.getPotentialPayout());
                            betsWon++;

                            log.info("Bet {} WON — paying out {} EUR to user {}",
                                    bet.getId(), bet.getPotentialPayout(), user.getUsername());
                        } else {
                            bet.setStatus(BetStatus.LOST);
                            betsLost++;
                            log.debug("Bet {} LOST (backed driver #{}, winner was #{})",
                                    bet.getId(), bet.getDriverNumber(), winnerDriverNumber);
                        }

                        bet.setSettledAt(settledAt);
                        betRepository.save(bet);

                        settlements.add(OutcomeResultDto.BetSettlementDto.builder()
                                .betId(bet.getId())
                                .userId(bet.getUser().getId())
                                .username(bet.getUser().getUsername())
                                .stakeAmount(bet.getStakeAmount())
                                .payout(won ? bet.getPotentialPayout() : BigDecimal.ZERO)
                                .status(bet.getStatus())
                                .build());
                    }

                    return OutcomeResultDto.builder()
                            .sessionKey(sessionKey)
                            .winnerDriverNumber(winnerDriverNumber)
                            .totalBetsSettled(pendingBets.size())
                            .betsWon(betsWon)
                            .betsLost(betsLost)
                            .totalPaidOut(totalPaidOut)
                            .settlements(settlements)
                            .build();
                });
            } catch (DataIntegrityViolationException ex) {
                log.warn("Database unique constraint violation for session outcome {}: {}", sessionKey, ex.getMessage());
                throw new BettingException("An outcome has already been recorded for session '" + sessionKey + "'.");
            }
        });
    }

    /**
     * Retrieves the recorded outcome for a session.
     *
     * @param sessionKey the session identifier
     * @return the outcome result
     * @throws ResourceNotFoundException if no outcome has been recorded yet
     */
    @Transactional(readOnly = true)
    public EventOutcome getOutcome(String sessionKey) {
        return eventOutcomeRepository.findBySessionKey(sessionKey)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No outcome recorded for session '" + sessionKey + "'."));
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Verifies that the declared winner driver participated in the session.
     */
    private void validateWinnerInSession(String sessionKey, Integer winnerDriverNumber) {
        Future<List<F1Driver>> future = f1ValidationExecutor.submit(
                () -> f1EventProvider.getDriversForSession(sessionKey));
        List<F1Driver> drivers;
        try {
            drivers = future.get(driverValidationTimeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new BettingException("Timed out validating winner driver with F1 provider for session '" + sessionKey + "'. Please try again.");
        } catch (Exception e) {
            future.cancel(true);
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof BettingException be) {
                throw be;
            }
            throw new BettingException("Could not verify winner driver for session '" + sessionKey + "': " + cause.getMessage());
        }

        boolean found = drivers != null && drivers.stream()
                .anyMatch(d -> winnerDriverNumber.equals(d.getDriverNumber()));
        if (!found) {
            throw new BettingException(
                    "Winner driver #" + winnerDriverNumber + " is not registered in session '" + sessionKey + "'.");
        }
    }
}
