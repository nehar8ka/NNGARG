package com.f1betting.domain;

import com.f1betting.enums.BetStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A single bet placed by a user on a driver to win a specific F1 session.
 */
@Entity
@Table(name = "f1_bets")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Bet {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "bet_seq")
    @SequenceGenerator(name = "bet_seq", sequenceName = "bet_seq", allocationSize = 1)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /**
     * The OpenF1 session_key identifying the event being bet on.
     * Stored as a string to remain agnostic of the external API's type.
     */
    @Column(nullable = false, length = 50)
    private String sessionKey;

    /**
     * The official car/driver number of the driver being backed to win.
     */
    @Column(nullable = false)
    private Integer driverNumber;

    /** Amount staked in EUR — immediately deducted from user balance. */
    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal stakeAmount;

    /**
     * The decimal odds at the time the bet was placed (2, 3 or 4).
     * Stored so winnings can be calculated consistently on settlement.
     */
    @Column(nullable = false)
    private Integer odds;

    /**
     * Potential payout if the bet wins: {@code stakeAmount × odds}.
     * Pre-calculated and stored at placement time.
     */
    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal potentialPayout;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private BetStatus status;

    @Column(nullable = false)
    private LocalDateTime placedAt;

    /** Set when the event outcome is recorded and the bet is settled. */
    private LocalDateTime settledAt;
}
