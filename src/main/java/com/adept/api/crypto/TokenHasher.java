package com.adept.api.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.adept.api.config.AppProperties;

@Component
public final class TokenHasher {

    private static final String ALGORITHM = "HmacSHA256";

    private final byte[] pepper;

    @Autowired
    public TokenHasher(AppProperties properties) {
        this(Base64.getDecoder().decode(properties.tokenHashPepperBase64()));
    }

    TokenHasher(byte[] pepper) {
        this.pepper = pepper.clone();
    }

    public String hash(Domain domain, String value) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(pepper, ALGORITHM));
            byte[] digest = mac.doFinal((domain.prefix + value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA-256 is unavailable", exception);
        }
    }

    public String hashRefreshToken(String rawToken) {
        return hash(Domain.REFRESH, rawToken);
    }

    public String hashVerificationToken(String rawToken) {
        return hash(Domain.VERIFY, rawToken);
    }

    public String hashResetToken(String rawToken) {
        return hash(Domain.RESET, rawToken);
    }

    public String hashAuditIp(String value) {
        return hash(Domain.AUDIT_IP, value);
    }

    public String hashAuditEmail(String normalizedEmail) {
        return hash(Domain.AUDIT_EMAIL, normalizedEmail);
    }

    public String hashUserAgent(String value) {
        return hash(Domain.USER_AGENT, value);
    }

    public String hashIntegrationState(String rawState) {
        return hash(Domain.INTEGRATION_STATE, rawState);
    }

    public enum Domain {
        REFRESH("adept:v1:refresh:"),
        VERIFY("adept:v1:verify:"),
        RESET("adept:v1:reset:"),
        AUDIT_IP("adept:v1:audit-ip:"),
        AUDIT_EMAIL("adept:v1:audit-email:"),
        USER_AGENT("adept:v1:user-agent:"),
        INTEGRATION_STATE("adept:v1:integration-state:");

        private final String prefix;

        Domain(String prefix) {
            this.prefix = prefix;
        }
    }
}
