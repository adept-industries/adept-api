package com.adept.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.adept.api.auth.dto.SignupRequest;
import com.adept.api.common.error.ApiException;
import com.adept.api.common.error.ProblemCode;
import com.adept.api.crypto.SecureTokenGenerator;

class EmailVerificationIntegrationTest extends PartCIntegrationTestSupport {

    private static final String VERIFICATION_SUBJECT = "Verify your Adept email";

    @Autowired
    private AuthService authService;

    @Autowired
    private ActionTokenService actionTokenService;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void verificationConsumesOnceAndRepeatedUseIsIdempotent() {
        String email = uniqueEmail("verify-idempotent");
        String rawToken = signupAndGetToken(email);

        authService.verifyEmail(rawToken, requestContext());
        Object firstVerifiedAt = jdbc.queryForObject(
            "SELECT email_verified_at FROM users WHERE email = ?",
            Object.class,
            email
        );
        assertThat(firstVerifiedAt).isNotNull();
        assertThat(consumed(rawToken)).isTrue();
        assertThat(auditCount("EMAIL_VERIFIED")).isOne();

        authService.verifyEmail(rawToken, requestContext());

        assertThat(jdbc.queryForObject(
            "SELECT email_verified_at FROM users WHERE email = ?",
            Object.class,
            email
        )).isEqualTo(firstVerifiedAt);
        assertThat(auditCount("EMAIL_VERIFIED")).isOne();
    }

    @Test
    void expiryBoundaryUnknownWrongPurposeAndConsumedUnverifiedAreIndistinguishable() {
        String expiredEmail = uniqueEmail("verify-expired");
        String expired = signupAndGetToken(expiredEmail);
        jdbc.update("""
            UPDATE user_action_tokens
            SET expires_at = CURRENT_TIMESTAMP
            WHERE token_hash = ?
            """, actionTokenService.hash(com.adept.api.common.domain.ActionTokenPurpose.VERIFY_EMAIL, expired));

        String consumedEmail = uniqueEmail("verify-consumed");
        String consumed = signupAndGetToken(consumedEmail);
        jdbc.update("""
            UPDATE user_action_tokens
            SET consumed_at = CURRENT_TIMESTAMP
            WHERE token_hash = ?
            """, actionTokenService.hash(com.adept.api.common.domain.ActionTokenPurpose.VERIFY_EMAIL, consumed));

        String resetEmail = uniqueEmail("verify-wrong-purpose");
        signupAndGetToken(resetEmail);
        mailSender.reset();
        authService.forgotPassword(resetEmail, requestContext());
        String resetToken = awaitToken(resetEmail, "Reset your Adept password");

        assertInvalid(expired);
        assertInvalid(consumed);
        assertInvalid(resetToken);
        assertInvalid(new SecureTokenGenerator().generate());
        assertThat(auditCount("EMAIL_VERIFIED")).isZero();
    }

    @Test
    void twoConcurrentVerificationRequestsPerformOneTransition() throws Exception {
        String email = uniqueEmail("verify-concurrent");
        String rawToken = signupAndGetToken(email);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<String>> results = List.of(
                executor.submit(() -> concurrentVerify(rawToken, ready, start)),
                executor.submit(() -> concurrentVerify(rawToken, ready, start))
            );
            ready.await();
            start.countDown();
            assertThat(List.of(results.get(0).get(), results.get(1).get()))
                .containsOnly("SUCCESS");
        } finally {
            executor.shutdownNow();
        }

        assertThat(consumed(rawToken)).isTrue();
        assertThat(auditCount("EMAIL_VERIFIED")).isOne();
    }

    @Test
    void resendInvalidatesTheOlderTokenAndCreatesOneReplacement() {
        String email = uniqueEmail("verify-resend");
        String original = signupAndGetToken(email);
        mailSender.reset();

        authService.resendVerification(email, requestContext());
        String replacement = awaitToken(email, VERIFICATION_SUBJECT);

        assertThat(replacement).isNotEqualTo(original);
        assertThat(consumed(original)).isTrue();
        assertThat(activeVerificationTokens(email)).isOne();
        assertThat(auditCount("VERIFICATION_EMAIL_REQUESTED")).isOne();
        assertInvalid(original);
        authService.verifyEmail(replacement, requestContext());
        assertThat(auditCount("EMAIL_VERIFIED")).isOne();
    }

    @Test
    void concurrentResendsLeaveExactlyOneUsableVerificationToken() throws Exception {
        String email = uniqueEmail("verify-resend-race");
        signupAndGetToken(email);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> results = List.of(
                executor.submit(() -> concurrentResend(email, ready, start)),
                executor.submit(() -> concurrentResend(email, ready, start))
            );
            ready.await();
            start.countDown();
            results.get(0).get();
            results.get(1).get();
        } finally {
            executor.shutdownNow();
        }

        assertThat(activeVerificationTokens(email)).isOne();
        assertThat(auditCount("VERIFICATION_EMAIL_REQUESTED")).isEqualTo(2);
    }

    @RepeatedTest(10)
    void verifyVersusResendHasOneDeterministicWinner() throws Exception {
        String email = uniqueEmail("verify-resend-winner");
        String original = signupAndGetToken(email);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        String verifyResult;
        try {
            Future<String> verification = executor.submit(() -> concurrentVerify(original, ready, start));
            Future<?> resend = executor.submit(() -> concurrentResend(email, ready, start));
            ready.await();
            start.countDown();
            verifyResult = verification.get();
            resend.get();
        } finally {
            executor.shutdownNow();
        }

        boolean verified = jdbc.queryForObject(
            "SELECT email_verified_at IS NOT NULL FROM users WHERE email = ?",
            Boolean.class,
            email
        );
        assertThat(consumed(original)).isTrue();
        if (verified) {
            assertThat(verifyResult).isEqualTo("SUCCESS");
            assertThat(activeVerificationTokens(email)).isZero();
            assertThat(auditCount("EMAIL_VERIFIED")).isOne();
            assertThat(auditCount("VERIFICATION_EMAIL_REQUESTED")).isZero();
        } else {
            assertThat(verifyResult).isEqualTo(ProblemCode.ACTION_TOKEN_INVALID.name());
            assertThat(activeVerificationTokens(email)).isOne();
            assertThat(auditCount("EMAIL_VERIFIED")).isZero();
            assertThat(auditCount("VERIFICATION_EMAIL_REQUESTED")).isOne();
        }
    }

    @Test
    void resendKnownUnknownAndAlreadyVerifiedResponsesAreIndistinguishable() throws Exception {
        String known = uniqueEmail("resend-known");
        signupAndGetToken(known);
        String verified = uniqueEmail("resend-verified");
        String verifiedToken = signupAndGetToken(verified);
        authService.verifyEmail(verifiedToken, requestContext());
        String unknown = uniqueEmail("resend-unknown");

        List<MvcResult> responses = List.of(
            resendRequest(known),
            resendRequest(unknown),
            resendRequest(verified)
        );

        assertThat(responses)
            .allSatisfy(response -> {
                assertThat(response.getResponse().getStatus()).isEqualTo(202);
                assertThat(response.getResponse().getContentAsByteArray()).isEmpty();
                assertThat(response.getResponse().getHeader(HttpHeaders.CACHE_CONTROL))
                    .isEqualTo("no-store");
            });
    }

    private String signupAndGetToken(String email) {
        authService.signup(
            new SignupRequest(email, VALID_PASSWORD, "Asha", "Asha Workspace", "UTC"),
            requestContext()
        );
        return awaitToken(email, VERIFICATION_SUBJECT);
    }

    private MvcResult resendRequest(String email) throws Exception {
        CsrfPair csrf = fetchCsrf(mockMvc);
        return mockMvc.perform(post("/api/v1/auth/resend-verification")
                .cookie(csrf.cookie())
                .header("X-XSRF-TOKEN", csrf.token())
                .header(HttpHeaders.ORIGIN, FRONTEND_ORIGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\"}"))
            .andExpect(status().isAccepted())
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andReturn();
    }

    private String concurrentVerify(
            String rawToken,
            CountDownLatch ready,
            CountDownLatch start) throws InterruptedException {
        ready.countDown();
        start.await();
        try {
            authService.verifyEmail(rawToken, requestContext());
            return "SUCCESS";
        } catch (ApiException exception) {
            return exception.code().name();
        }
    }

    private void concurrentResend(
            String email,
            CountDownLatch ready,
            CountDownLatch start) {
        ready.countDown();
        try {
            start.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
        authService.resendVerification(email, requestContext());
    }

    private void assertInvalid(String rawToken) {
        assertThatThrownBy(() -> authService.verifyEmail(rawToken, requestContext()))
            .isInstanceOfSatisfying(ApiException.class,
                exception -> assertThat(exception.code()).isEqualTo(ProblemCode.ACTION_TOKEN_INVALID));
    }

    private boolean consumed(String rawToken) {
        String hash = actionTokenService.hash(
            com.adept.api.common.domain.ActionTokenPurpose.VERIFY_EMAIL,
            rawToken
        );
        return jdbc.queryForObject(
            "SELECT consumed_at IS NOT NULL FROM user_action_tokens WHERE token_hash = ?",
            Boolean.class,
            hash
        );
    }

    private int activeVerificationTokens(String email) {
        return jdbc.queryForObject("""
            SELECT COUNT(*)
            FROM user_action_tokens t
            JOIN users u ON u.id = t.user_id
            WHERE u.email = ? AND t.purpose = 'VERIFY_EMAIL' AND t.consumed_at IS NULL
            """, Integer.class, email);
    }

    private int auditCount(String action) {
        return jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_logs WHERE action = ?",
            Integer.class,
            action
        );
    }
}
