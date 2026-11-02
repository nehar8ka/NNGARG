package com.f1betting.exception;

/**
 * Thrown when a requested resource (user, bet, outcome) does not exist.
 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
