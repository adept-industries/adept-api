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
    void missingUserUsesOneDummyHashComparison() {
        RecordingEncoder encoder = new RecordingEncoder(true);
        PasswordService service = new PasswordService(new PasswordPolicy(), encoder);

        assertThat(service.matchesAuthenticationCandidate("candidate", null)).isFalse();
        assertThat(encoder.matchCalls).isEqualTo(1);
        assertThat(encoder.lastHash).startsWith("$2a$12$");
    }

    @Test
    void authenticationCandidatesDoNotRunCreationPolicyAndCompareExactlyOnce() {
        for (String candidate : new String[] {"short", "password", "x".repeat(73)}) {
            RecordingEncoder encoder = new RecordingEncoder(false);
            PasswordService service = new PasswordService(new PasswordPolicy(), encoder);

            assertThat(service.matchesAuthenticationCandidate(candidate, "real-hash")).isFalse();
            assertThat(encoder.matchCalls).isEqualTo(1);
        }
    }

    @Test
    void oversizedCandidateUsesFixedSeventyTwoByteSubstituteAndForcesFailure() {
        RecordingEncoder encoder = new RecordingEncoder(true);
        PasswordService service = new PasswordService(new PasswordPolicy(), encoder);

        assertThat(service.matchesAuthenticationCandidate("x".repeat(73), "real-hash")).isFalse();
        assertThat(encoder.lastCandidate.getBytes(java.nio.charset.StandardCharsets.UTF_8)).hasSize(72);
        assertThat(encoder.matchCalls).isEqualTo(1);
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
