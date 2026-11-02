package com.f1betting.exception;

/**
 * Thrown when a business rule is violated during a betting operation
 * (e.g. insufficient balance, outcome already recorded).
 */
public class BettingException extends RuntimeException {

    public BettingException(String message) {
        super(message);
    }
}
