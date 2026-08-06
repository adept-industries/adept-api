package com.adept.api.crypto;

import java.nio.charset.StandardCharsets;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.adept.api.common.validation.PasswordPolicy;
import com.adept.api.config.AppProperties;

@Service
public final class PasswordService {

    private static final String DUMMY_HASH =
        "$2a$12$JWnskhFrsJ3fS3.kAmgNBePNg3opGY59injBZtVdMJjOqilGqTF2.";
    private static final String FIXED_COMPARISON_CANDIDATE =
        "adept-fixed-comparison-candidate-000000000000000000000000000000000000000";

    private final PasswordPolicy policy;
    private final PasswordEncoder encoder;

    @Autowired
    public PasswordService(AppProperties properties, PasswordPolicy policy) {
        this(policy, new BCryptPasswordEncoder(properties.auth().bcryptCost()));
    }

    PasswordService(PasswordPolicy policy, PasswordEncoder encoder) {
        this.policy = policy;
        this.encoder = encoder;
    }

    public String encodeNewPassword(String password) {
        policy.requireValid(password);
        return encoder.encode(password);
    }

    public boolean matchesAuthenticationCandidate(String candidate, String realHash) {
        boolean missingCandidate = candidate == null;
        boolean oversized = !missingCandidate
            && candidate.getBytes(StandardCharsets.UTF_8).length > PasswordPolicy.MAX_UTF8_BYTES;
        String comparisonCandidate = missingCandidate || oversized
            ? FIXED_COMPARISON_CANDIDATE
            : candidate;
        String selectedHash = realHash == null || realHash.isBlank() ? DUMMY_HASH : realHash;
        boolean matches = encoder.matches(comparisonCandidate, selectedHash);
        return !missingCandidate && !oversized && realHash != null && !realHash.isBlank() && matches;
    }
}
