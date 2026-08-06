package com.adept.api.common.validation;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public final class PasswordPolicy {

    public static final int MIN_CODE_POINTS = 12;
    public static final int MAX_UTF8_BYTES = 72;

    private static final String BLOCKLIST_RESOURCE = "security/common-passwords.txt";

    private final Set<String> commonPasswords;

    public PasswordPolicy() {
        this.commonPasswords = loadCommonPasswords();
    }

    PasswordPolicy(Set<String> commonPasswords) {
        this.commonPasswords = commonPasswords.stream()
            .map(value -> value.toLowerCase(Locale.ROOT))
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public Optional<String> violation(String candidate) {
        if (candidate == null) {
            return Optional.of("must not be null");
        }
        if (candidate.codePointCount(0, candidate.length()) < MIN_CODE_POINTS) {
            return Optional.of("must contain at least 12 characters");
        }
        if (candidate.getBytes(StandardCharsets.UTF_8).length > MAX_UTF8_BYTES) {
            return Optional.of("must contain at most 72 UTF-8 bytes");
        }
        if (commonPasswords.contains(candidate.toLowerCase(Locale.ROOT))) {
            return Optional.of("is too common");
        }
        return Optional.empty();
    }

    public boolean isValid(String candidate) {
        return violation(candidate).isEmpty();
    }

    public void requireValid(String candidate) {
        violation(candidate).ifPresent(message -> {
            throw new IllegalArgumentException("password " + message);
        });
    }

    public int commonPasswordCount() {
        return commonPasswords.size();
    }

    private static Set<String> loadCommonPasswords() {
        ClassPathResource resource = new ClassPathResource(BLOCKLIST_RESOURCE);
        Set<String> values = new HashSet<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                resource.getInputStream(),
                StandardCharsets.UTF_8))) {
            reader.lines()
                .filter(line -> !line.isBlank())
                .map(line -> line.toLowerCase(Locale.ROOT))
                .forEach(values::add);
        } catch (IOException exception) {
            throw new IllegalStateException("could not load the bundled common-password blocklist", exception);
        }
        if (values.isEmpty()) {
            throw new IllegalStateException("the bundled common-password blocklist is empty");
        }
        return Collections.unmodifiableSet(values);
    }
}
