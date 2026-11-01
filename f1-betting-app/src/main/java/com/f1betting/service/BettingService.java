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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

/**
 * Business logic for placing and retrieving bets.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BettingService {

    private static final int[] POSSIBLE_ODDS = {2, 3, 4};

    private final BetRepository betRepository;
    private final UserRepository userRepository;
    private final EventOutcomeRepository eventOutcomeRepository;
    private final F1EventProvider f1EventProvider;
    private final Random random = new Random();

    /**
     * Places a single bet for a user on a driver to win a specified F1 session.
     *
     * <p>Business rules enforced:
     * <ol>
     *   <li>The user must exist.</li>
     *   <li>The event must not already have a recorded outcome (no post-race bets).</li>
     *   <li>The driver must be registered in the specified session.</li>
     *   <li>The user must have sufficient balance to cover the stake.</li>
     * </ol>
     *
     * <p>The stake is deducted from the user's balance immediately upon placement.
     * Odds are (re)generated at placement time and persisted with the bet.
     *
     * @param request the bet placement request
     * @return the created bet as a DTO
     */
    @Transactional
    public BetDto placeBet(PlaceBetRequest request) {
        // Rule 1: User must exist
        User user = userRepository.findById(request.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "User not found with id: " + request.getUserId()));

        // Rule 2: Cannot bet on an event that already has a recorded outcome
        if (eventOutcomeRepository.existsBySessionKey(request.getSessionKey())) {
            throw new BettingException(
                    "Event with sessionKey '" + request.getSessionKey() +
                    "' already has a recorded outcome. No further bets are accepted.");
        }

        // Rule 3: Driver must participate in the session
        validateDriverInSession(request.getSessionKey(), request.getDriverNumber());

        // Rule 4: Sufficient balance
        if (user.getBalance().compareTo(request.getStakeAmount()) < 0) {
            throw new BettingException(
                    "Insufficient balance. Available: " + user.getBalance() +
                    " EUR, Required: " + request.getStakeAmount() + " EUR.");
        }

        // Generate odds and calculate potential payout
        int odds = randomOdds();
        BigDecimal potentialPayout = request.getStakeAmount().multiply(BigDecimal.valueOf(odds));

        // Deduct stake from balance immediately
        user.setBalance(user.getBalance().subtract(request.getStakeAmount()));
        userRepository.save(user);

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
     * Verifies that the given driver number is present in the session by calling
     * the F1 event provider. Throws {@link BettingException} if not found.
     */
    private void validateDriverInSession(String sessionKey, Integer driverNumber) {
        List<F1Driver> drivers = f1EventProvider.getDriversForSession(sessionKey);
        boolean found = drivers.stream()
                .anyMatch(d -> driverNumber.equals(d.getDriverNumber()));
        if (!found) {
            throw new BettingException(
                    "Driver #" + driverNumber + " is not registered in session '" + sessionKey + "'.");
        }
    }

    private int randomOdds() {
        return POSSIBLE_ODDS[random.nextInt(POSSIBLE_ODDS.length)];
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
