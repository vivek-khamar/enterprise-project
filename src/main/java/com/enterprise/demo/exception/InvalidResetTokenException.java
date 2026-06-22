package com.enterprise.demo.exception;

public class InvalidResetTokenException extends RuntimeException {

    public InvalidResetTokenException() {
        super("Invalid reset token.");
    }
}
