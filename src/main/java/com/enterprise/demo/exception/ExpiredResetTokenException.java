package com.enterprise.demo.exception;

public class ExpiredResetTokenException extends RuntimeException {

    public ExpiredResetTokenException() {
        super("Reset token has expired.");
    }
}
