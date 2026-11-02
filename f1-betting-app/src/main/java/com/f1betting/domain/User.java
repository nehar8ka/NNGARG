package com.f1betting.domain;

import com.f1betting.exception.BettingException;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * A registered bettor in the system.
 * Users are pre-seeded at startup with a gift balance of 100 EUR.
 * No deposits or withdrawals are supported.
 */
@Entity
@Table(name = "f1_users")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "user_seq")
    @SequenceGenerator(name = "user_seq", sequenceName = "user_seq", allocationSize = 1)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String username;

    /**
     * Current wallet balance in EUR.
     * Decremented when a bet is placed; incremented when a bet is won.
     */
    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal balance;

    @ToString.Exclude
    @OneToMany(mappedBy = "user", fetch = FetchType.LAZY)
    @Builder.Default
    private List<Bet> bets = new ArrayList<>();

    /**
     * Deducts the given stake amount from the user's balance.
     * Enforces the domain invariant that balance cannot become negative.
     *
     * @param amount the stake amount to debit
     * @throws BettingException if amount is invalid or exceeds available balance
     */
    public void debit(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BettingException("Debit amount must be greater than zero.");
        }
        if (this.balance == null || this.balance.compareTo(amount) < 0) {
            throw new BettingException(
                    "Insufficient balance. Available: " + (this.balance != null ? this.balance : BigDecimal.ZERO) +
                    " EUR, Required: " + amount + " EUR.");
        }
        this.balance = this.balance.subtract(amount);
    }

    /**
     * Credits the given payout amount to the user's balance.
     *
     * @param amount the payout amount to credit
     * @throws BettingException if amount is invalid
     */
    public void credit(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BettingException("Credit amount must be greater than zero.");
        }
        this.balance = (this.balance != null ? this.balance : BigDecimal.ZERO).add(amount);
    }
}
