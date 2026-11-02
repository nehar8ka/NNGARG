package com.f1betting.domain;

import com.f1betting.exception.BettingException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class UserTest {

    @Test
    void testDebitSuccessful() {
        User user = User.builder().balance(new BigDecimal("100.00")).build();
        user.debit(new BigDecimal("25.00"));
        assertEquals(new BigDecimal("75.00"), user.getBalance());
    }

    @Test
    void testDebitExactBalanceSuccessful() {
        User user = User.builder().balance(new BigDecimal("100.00")).build();
        user.debit(new BigDecimal("100.00"));
        assertEquals(new BigDecimal("0.00"), user.getBalance());
    }

    @Test
    void testDebitInsufficientBalanceThrowsBettingException() {
        User user = User.builder().balance(new BigDecimal("50.00")).build();
        BettingException ex = assertThrows(BettingException.class, () -> user.debit(new BigDecimal("50.01")));
        assertTrue(ex.getMessage().contains("Insufficient balance"));
    }

    @Test
    void testDebitNegativeOrZeroThrowsBettingException() {
        User user = User.builder().balance(new BigDecimal("50.00")).build();
        assertThrows(BettingException.class, () -> user.debit(BigDecimal.ZERO));
        assertThrows(BettingException.class, () -> user.debit(new BigDecimal("-10.00")));
        assertThrows(BettingException.class, () -> user.debit(null));
    }

    @Test
    void testCreditSuccessful() {
        User user = User.builder().balance(new BigDecimal("50.00")).build();
        user.credit(new BigDecimal("30.00"));
        assertEquals(new BigDecimal("80.00"), user.getBalance());
    }

    @Test
    void testCreditNegativeOrZeroThrowsBettingException() {
        User user = User.builder().balance(new BigDecimal("50.00")).build();
        assertThrows(BettingException.class, () -> user.credit(BigDecimal.ZERO));
        assertThrows(BettingException.class, () -> user.credit(new BigDecimal("-5.00")));
        assertThrows(BettingException.class, () -> user.credit(null));
    }
}
