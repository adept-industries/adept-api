package com.adept.api.crypto;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

@Component
public final class SecureTokenGenerator {

    public static final int TOKEN_BYTES = 32;
    private static final Pattern TOKEN_SHAPE = Pattern.compile("[A-Za-z0-9_-]{43}");

    private final SecureRandom secureRandom;

    public SecureTokenGenerator() {
        this(new SecureRandom());
    }

    SecureTokenGenerator(SecureRandom secureRandom) {
        this.secureRandom = secureRandom;
    }

    public String generate() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static boolean isWellFormed(String value) {
        if (value == null || !TOKEN_SHAPE.matcher(value).matches()) {
            return false;
        }
        try {
            return Base64.getUrlDecoder().decode(value).length == TOKEN_BYTES;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
