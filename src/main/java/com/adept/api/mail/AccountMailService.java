package com.adept.api.mail;

import java.net.URI;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import com.adept.api.config.AppProperties;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

@Service
public class AccountMailService {

    private static final Logger log = LoggerFactory.getLogger(AccountMailService.class);

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

    public void sendInvitation(InvitationMailRequested event) {
        send(
            event.recipient(),
            "You've been invited to join " + event.workspaceName() + " on Adept",
            "You have been invited to join " + event.workspaceName() + " as a Lead on Adept.\n\n"
                + link("/invitations/accept#token=", event.rawToken())
                + "\n\nThis invitation will expire in 7 days. If you did not expect this invitation, you can ignore this email."
        );
    }

    /**
     * Send a plain-text-only alert email.
     */
    public void sendAlert(String recipient, String subject, String body) {
        send(recipient, subject, body);
    }

    /**
     * Send an alert email with HTML body (multipart text + HTML alternative).
     * Falls back to plain-text-only if htmlBody is null/blank or MIME construction fails.
     */
    public void sendAlertHtml(String recipient, String subject, String textBody, String htmlBody) {
        if (htmlBody == null || htmlBody.isBlank()) {
            send(recipient, subject, textBody);
            return;
        }

        try {
            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");
            helper.setFrom(properties.emailFrom());
            helper.setTo(recipient);
            helper.setSubject(subject);
            helper.setText(textBody, htmlBody);
            mailSender.send(mimeMessage);
        } catch (MessagingException ex) {
            log.warn("alert_html_mail_fallback recipient={} reason={}", recipient, ex.getMessage());
            send(recipient, subject, textBody);
        }
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
