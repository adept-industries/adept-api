package com.adept.api.crypto;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.adept.api.common.validation.PasswordPolicy;
import com.adept.api.support.TestAppProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PasswordServiceTest {

    @Test
    void realBcryptUsesCostTwelveAndHonorsTheSeventyTwoByteCreationBoundary() {
        PasswordService service = new PasswordService(TestAppProperties.create(), new PasswordPolicy());
        String boundaryPassword = "x".repeat(72);

        String hash = service.encodeNewPassword(boundaryPassword);

        assertThat(hash).matches("^\\$2[aby]\\$12\\$.*");
        assertThat(service.matchesAuthenticationCandidate(boundaryPassword, hash)).isTrue();
        assertThatThrownBy(() -> service.encodeNewPassword("x".repeat(73)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageNotContaining("x".repeat(73));
    }

    @Test
    void authenticationCandidatesAlwaysUseOneSafeComparison() {
        RecordingEncoder encoder = new RecordingEncoder(true);
        PasswordService service = new PasswordService(new PasswordPolicy(), encoder);

        assertThat(service.matchesAuthenticationCandidate("candidate", null)).isFalse();
        assertThat(encoder.matchCalls).isEqualTo(1);
        assertThat(encoder.lastHash).startsWith("$2a$12$");

        for (String candidate : new String[] {"short", "password", "x".repeat(73)}) {
            RecordingEncoder candidateEncoder = new RecordingEncoder(false);
            PasswordService candidateService = new PasswordService(new PasswordPolicy(), candidateEncoder);

            assertThat(candidateService.matchesAuthenticationCandidate(candidate, "real-hash")).isFalse();
            assertThat(candidateEncoder.matchCalls).isEqualTo(1);
        }

        RecordingEncoder oversizedEncoder = new RecordingEncoder(true);
        PasswordService oversizedService = new PasswordService(new PasswordPolicy(), oversizedEncoder);

        assertThat(oversizedService.matchesAuthenticationCandidate("x".repeat(73), "real-hash")).isFalse();
        assertThat(oversizedEncoder.lastCandidate.getBytes(java.nio.charset.StandardCharsets.UTF_8)).hasSize(72);
        assertThat(oversizedEncoder.matchCalls).isEqualTo(1);
    }

    private static final class RecordingEncoder implements PasswordEncoder {

        private final boolean matches;
        private int matchCalls;
        private String lastCandidate;
        private String lastHash;

        private RecordingEncoder(boolean matches) {
            this.matches = matches;
        }

        @Override
        public String encode(CharSequence rawPassword) {
            return "encoded";
        }

        @Override
        public boolean matches(CharSequence rawPassword, String encodedPassword) {
            matchCalls++;
            lastCandidate = rawPassword.toString();
            lastHash = encodedPassword;
            return matches;
        }
    }
}
