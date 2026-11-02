package com.f1betting.service;

import com.f1betting.adapter.F1EventProvider;
import com.f1betting.adapter.model.F1Driver;
import com.f1betting.domain.Bet;
import com.f1betting.domain.User;
import com.f1betting.dto.BetDto;
import com.f1betting.dto.PlaceBetRequest;
import com.f1betting.enums.BetStatus;
import com.f1betting.exception.BettingException;
import com.f1betting.exception.ResourceNotFoundException;
import com.f1betting.repository.BetRepository;
import com.f1betting.repository.EventOutcomeRepository;
import com.f1betting.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * Business logic for placing and retrieving bets.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BettingService {

    private final BetRepository betRepository;
    private final UserRepository userRepository;
    private final EventOutcomeRepository eventOutcomeRepository;
    private final F1EventProvider f1EventProvider;
    private final OddsGenerator oddsGenerator;
    private final SessionLockManager sessionLockManager;
    private final TransactionTemplate transactionTemplate;
    private final ExecutorService f1ValidationExecutor;

    @Value("${betting.driver-validation-timeout-seconds:8}")
    private long driverValidationTimeoutSeconds = 8;

    /**
     * Places a single bet for a user on a driver to win a specified F1 session.
     *
     * <p>Performance & Concurrency Design:
     * <ol>
     *   <li>External driver validation is performed OUTSIDE the database transaction to prevent Hikari connection starvation.</li>
     *   <li>Uses a dedicated thread pool rather than ForkJoinPool.commonPool() with explicit cancellation on timeout.</li>
     *   <li>Fine-grained lock on {@code sessionKey} prevents TOCTOU race conditions with concurrent outcome recordings.</li>
     *   <li>Pessimistic write lock on the user ensures atomicity of balance debits.</li>
     * </ol>
     *
     * @param request the bet placement request
     * @return the created bet as a DTO
     */
    public BetDto placeBet(PlaceBetRequest request) {
        // Fast-fail check before external network I/O
        if (eventOutcomeRepository.existsBySessionKey(request.getSessionKey())) {
            throw new BettingException(
                    "Event with sessionKey '" + request.getSessionKey() +
                    "' already has a recorded outcome. No further bets are accepted.");
        }

        // Validate driver registration with external provider (OUTSIDE database transaction)
        validateDriverInSession(request.getSessionKey(), request.getDriverNumber());

        // Coordinate with OutcomeService via fine-grained lock to eliminate race conditions
        return sessionLockManager.executeWithLock(request.getSessionKey(), () -> {
            return transactionTemplate.execute(status -> {
                // Atomic re-check inside transaction and lock
                if (eventOutcomeRepository.existsBySessionKey(request.getSessionKey())) {
                    throw new BettingException(
                            "Event with sessionKey '" + request.getSessionKey() +
                            "' already has a recorded outcome. No further bets are accepted.");
                }

                // Acquire pessimistic write lock right before balance debit
                User user = userRepository.findByIdForUpdate(request.getUserId())
                        .orElseThrow(() -> new ResourceNotFoundException(
                                "User not found with id: " + request.getUserId()));

                // Debit stake from user balance (enforces balance invariant, throws if insufficient)
                user.debit(request.getStakeAmount());
                userRepository.save(user);

                // Generate odds and calculate potential payout
                int odds = oddsGenerator.generateOdds();
                BigDecimal potentialPayout = request.getStakeAmount().multiply(BigDecimal.valueOf(odds));

                // Persist the bet
                Bet bet = Bet.builder()
                        .user(user)
                        .sessionKey(request.getSessionKey())
                        .driverNumber(request.getDriverNumber())
                        .stakeAmount(request.getStakeAmount())
                        .odds(odds)
                        .potentialPayout(potentialPayout)
                        .status(BetStatus.PENDING)
                        .placedAt(LocalDateTime.now())
                        .build();

                Bet saved = betRepository.save(bet);
                log.info("Bet {} placed: user={} session={} driver={} stake={} odds={}",
                        saved.getId(), user.getUsername(), request.getSessionKey(),
                        request.getDriverNumber(), request.getStakeAmount(), odds);

                return toDto(saved);
            });
        });
    }

    @Transactional(readOnly = true)
    public BetDto getBetById(Long id) {
        return toDto(betRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Bet not found with id: " + id)));
    }

    @Transactional(readOnly = true)
    public List<BetDto> getBetsByUser(Long userId) {
        if (!userRepository.existsById(userId)) {
            throw new ResourceNotFoundException("User not found with id: " + userId);
        }
        return betRepository.findByUserId(userId).stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Verifies that the given driver number is present in the session using a dedicated bounded
     * thread pool with cancellation on timeout.
     */
    private void validateDriverInSession(String sessionKey, Integer driverNumber) {
        Future<List<F1Driver>> future = f1ValidationExecutor.submit(
                () -> f1EventProvider.getDriversForSession(sessionKey));
        List<F1Driver> drivers;
        try {
            drivers = future.get(driverValidationTimeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new BettingException("Timed out validating driver registration with F1 provider for session '" + sessionKey + "'. Please try again.");
        } catch (Exception e) {
            future.cancel(true);
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof BettingException be) {
                throw be;
            }
            throw new BettingException("Could not verify driver registration for session '" + sessionKey + "': " + cause.getMessage());
        }

        boolean found = drivers != null && drivers.stream()
                .anyMatch(d -> driverNumber.equals(d.getDriverNumber()));
        if (!found) {
            throw new BettingException(
                    "Driver #" + driverNumber + " is not registered in session '" + sessionKey + "'.");
        }
    }

    private BetDto toDto(Bet bet) {
        return BetDto.builder()
                .id(bet.getId())
                .userId(bet.getUser().getId())
                .username(bet.getUser().getUsername())
                .sessionKey(bet.getSessionKey())
                .driverNumber(bet.getDriverNumber())
                .odds(bet.getOdds())
                .stakeAmount(bet.getStakeAmount())
                .potentialPayout(bet.getPotentialPayout())
                .status(bet.getStatus())
                .placedAt(bet.getPlacedAt())
                .settledAt(bet.getSettledAt())
                .build();
    }
}
