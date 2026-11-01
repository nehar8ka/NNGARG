package com.f1betting.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Public-facing user representation (no sensitive fields).
 */
@Data
@Builder
public class UserDto {
    private Long id;
    private String username;
    private BigDecimal balance;
}
