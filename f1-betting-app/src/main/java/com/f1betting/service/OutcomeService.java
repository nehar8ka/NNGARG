package com.f1betting.service;

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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Business logic for recording event outcomes and settling bets.
 *
 * <p>When an outcome is recorded for a session:
 * <ol>
 *   <li>The outcome is persisted (idempotency guard — duplicate submissions rejected).</li>
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

    /**
     * Records the official outcome for a completed F1 event and settles all
     * pending bets for that session.
     *
     * @param sessionKey          the session identifier
     * @param winnerDriverNumber  the car number of the race winner
     * @return settlement summary
     * @throws BettingException if an outcome has already been recorded for this session
     */
    @Transactional
    public OutcomeResultDto recordOutcome(String sessionKey, Integer winnerDriverNumber) {
        // Idempotency: reject duplicate outcome submissions
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

                // Credit payout to the user's balance
                User user = userRepository.findByIdForUpdate(bet.getUser().getId()).
                        orElseThrow(()->new ResourceNotFoundException(
                                "User not found with id: "+ bet.getUser().getId()
                        ));
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
}
