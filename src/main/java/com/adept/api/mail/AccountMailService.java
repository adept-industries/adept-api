package com.adept.api.mail;

import java.net.URI;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import com.adept.api.config.AppProperties;

import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;

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
     * Send an alert email with HTML body (multipart/alternative: text + HTML).
     * Falls back to plain-text-only if htmlBody is null/blank or MIME construction fails.
     *
     * Uses a clean multipart/alternative structure (no outer multipart/mixed wrapper)
     * for maximum compatibility with production SMTP providers like AWS SES.
     */
    public void sendAlertHtml(String recipient, String subject, String textBody, String htmlBody) {
        if (htmlBody == null || htmlBody.isBlank()) {
            send(recipient, subject, textBody);
            return;
        }

        try {
            MimeMessage mimeMessage = mailSender.createMimeMessage();

            // Use InternetAddress.parse for lenient handling of "Display Name <email>" formats
            mimeMessage.setFrom(InternetAddress.parse(properties.emailFrom())[0]);
            mimeMessage.setRecipients(Message.RecipientType.TO, InternetAddress.parse(recipient));
            mimeMessage.setSubject(subject, "UTF-8");

            // Build a clean multipart/alternative directly (no multipart/mixed wrapper)
            MimeMultipart alternative = new MimeMultipart("alternative");

            MimeBodyPart textPart = new MimeBodyPart();
            textPart.setText(textBody, "UTF-8", "plain");
            alternative.addBodyPart(textPart);

            MimeBodyPart htmlPart = new MimeBodyPart();
            htmlPart.setContent(htmlBody, "text/html; charset=UTF-8");
            alternative.addBodyPart(htmlPart);

            mimeMessage.setContent(alternative);
            mailSender.send(mimeMessage);
        } catch (MessagingException | MailException ex) {
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
