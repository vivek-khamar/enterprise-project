package com.enterprise.demo.exception;

public class SelfModificationException extends RuntimeException {

    public SelfModificationException() {
        super("Cannot modify your own account status or role");
    }
}
