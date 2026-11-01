package com.f1betting.dto;

import com.f1betting.enums.BetStatus;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Public-facing representation of a placed bet.
 */
@Data
@Builder
public class BetDto {
    private Long id;
    private Long userId;
    private String username;
    private String sessionKey;
    private Integer driverNumber;
    private Integer odds;
    private BigDecimal stakeAmount;
    private BigDecimal potentialPayout;
    private BetStatus status;
    private LocalDateTime placedAt;
    private LocalDateTime settledAt;
}
