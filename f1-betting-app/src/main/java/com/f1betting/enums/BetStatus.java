package com.f1betting.enums;

/**
 * Lifecycle state of a placed bet.
 */
public enum BetStatus {
    /** Bet has been placed; event outcome not yet recorded. */
    PENDING,
    /** Driver won the event — winnings credited to user balance. */
    WON,
    /** Driver did not win the event — stake forfeited. */
    LOST
}
