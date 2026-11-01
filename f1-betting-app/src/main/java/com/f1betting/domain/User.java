package com.f1betting.domain;

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
}
