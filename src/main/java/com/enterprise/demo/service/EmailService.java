package com.enterprise.demo.service;

public interface EmailService {

    void sendPasswordResetEmail(String toEmail, String token);
}
