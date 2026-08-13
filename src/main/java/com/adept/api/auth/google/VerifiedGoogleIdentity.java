package com.adept.api.auth.google;

import java.net.URI;
import java.util.Locale;

import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import com.adept.api.common.error.ForbiddenException;
import com.adept.api.common.error.ProblemCode;

public record VerifiedGoogleIdentity(
    String subject,
    String email,
    String displayName,
    String avatarUrl
) {

    private static final int MAX_SUBJECT_LENGTH = 255;
    private static final int MAX_EMAIL_LENGTH = 320;
    private static final int MAX_DISPLAY_NAME_LENGTH = 160;
    private static final int MAX_AVATAR_URL_LENGTH = 2_048;

    public static VerifiedGoogleIdentity from(OidcUser user) {
        String subject = clean(user.getSubject());
        String email = clean(user.getEmail()).toLowerCase(Locale.ROOT);
        if (!Boolean.TRUE.equals(user.getEmailVerified())
                || subject.isBlank() || subject.length() > MAX_SUBJECT_LENGTH
                || email.isBlank() || email.length() > MAX_EMAIL_LENGTH || !email.contains("@")) {
            throw new ForbiddenException(ProblemCode.GOOGLE_AUTH_FAILED);
        }

        String displayName = clean(user.getFullName());
        if (displayName.isBlank()) {
            displayName = email.substring(0, email.indexOf('@'));
        }
        displayName = truncate(displayName, MAX_DISPLAY_NAME_LENGTH);

        return new VerifiedGoogleIdentity(
            subject,
            email,
            displayName,
            safeAvatarUrl(user.getPicture())
        );
    }

    private static String safeAvatarUrl(String value) {
        String cleaned = clean(value);
        if (cleaned.isBlank() || cleaned.length() > MAX_AVATAR_URL_LENGTH) {
            return null;
        }
        try {
            URI uri = URI.create(cleaned);
            return uri.isAbsolute()
                    && "https".equalsIgnoreCase(uri.getScheme())
                    && uri.getHost() != null
                    && uri.getRawUserInfo() == null
                ? uri.toString()
                : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.replaceAll("\\p{Cntrl}", "").trim();
    }

    private static String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}

