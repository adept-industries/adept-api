package com.adept.api.mail;

import java.net.URI;

import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import com.adept.api.config.AppProperties;

@Service
public class AccountMailService {

    private final JavaMailSender mailSender;
    private final AppProperties properties;

    public AccountMailService(JavaMailSender mailSender, AppProperties properties) {
        this.mailSender = mailSender;
        this.properties = properties;
    }

    public void sendVerification(VerificationMailRequested event) {
        send(
            event.recipient(),
            "Verify your Adept email",
            "Open this link to verify your Adept account:\n\n"
                + link("/verify-email#token=", event.rawToken())
                + "\n\nIf you did not create this account, you can ignore this email."
        );
    }

    public void sendPasswordReset(PasswordResetMailRequested event) {
        send(
            event.recipient(),
            "Reset your Adept password",
            "Open this link to reset your Adept password:\n\n"
                + link("/reset-password#token=", event.rawToken())
                + "\n\nIf you did not request this, you can ignore this email."
        );
    }

    public void sendPasswordChanged(PasswordChangedMailRequested event) {
        send(
            event.recipient(),
            "Your Adept password changed",
            "Your Adept password was changed. If this was not you, contact support."
        );
    }

    private void send(String recipient, String subject, String body) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.emailFrom());
        message.setTo(recipient);
        message.setSubject(subject);
        message.setText(body);
        mailSender.send(message);
    }

    private String link(String pathPrefix, String rawToken) {
        URI base = properties.frontendBaseUrl();
        String origin = base.toString();
        if (origin.endsWith("/")) {
            origin = origin.substring(0, origin.length() - 1);
        }
        return origin + pathPrefix + rawToken;
    }
}
