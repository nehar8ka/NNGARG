package com.f1betting.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * Summary returned after recording an event outcome and settling all bets.
 */
@Data
@Builder
public class OutcomeResultDto {

    private String sessionKey;
    private Integer winnerDriverNumber;

    /** Total number of PENDING bets that were settled. */
    private int totalBetsSettled;

    /** Number of bets that were marked WON. */
    private int betsWon;

    /** Number of bets that were marked LOST. */
    private int betsLost;

    /** Total amount of winnings paid out across all winning bets. */
    private BigDecimal totalPaidOut;

    /** Individual settlement details for each affected bet. */
    private List<BetSettlementDto> settlements;

    @Data
    @Builder
    public static class BetSettlementDto {
        private Long betId;
        private Long userId;
        private String username;
        private BigDecimal stakeAmount;
        private BigDecimal payout;
        private com.f1betting.enums.BetStatus status;
    }
}
