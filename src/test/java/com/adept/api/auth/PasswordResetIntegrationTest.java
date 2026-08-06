package com.adept.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.adept.api.auth.dto.SignupRequest;
import com.adept.api.common.domain.ActionTokenPurpose;
import com.adept.api.common.error.ApiException;
import com.adept.api.common.error.ProblemCode;
import com.adept.api.crypto.PasswordService;
import com.adept.api.crypto.SecureTokenGenerator;

class PasswordResetIntegrationTest extends PartCIntegrationTestSupport {

    private static final String RESET_SUBJECT = "Reset your Adept password";

    @Autowired
    private AuthService authService;

    @Autowired
    private ActionTokenService actionTokenService;

    @Autowired
    private PasswordService passwordService;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void successfulResetChangesPasswordConsumesAllTokensRevokesSessionsAndClearsCookies() throws Exception {
        String email = uniqueEmail("reset-success");
        signup(email);
        String rawToken = issueReset(email);
        addAnotherResetToken(email);
        addRefreshTokens(email, 2);
        String replacementPassword = "amber-river-compass-4815";
        CsrfPair csrf = fetchCsrf(mockMvc);

        MvcResult result = mockMvc.perform(post("/api/v1/auth/reset-password")
                .cookie(csrf.cookie())
                .header("X-XSRF-TOKEN", csrf.token())
                .header(HttpHeaders.ORIGIN, FRONTEND_ORIGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(resetJson(rawToken, replacementPassword)))
            .andExpect(status().isNoContent())
            .andReturn();

        String passwordHash = jdbc.queryForObject(
            "SELECT password_hash FROM users WHERE email = ?",
            String.class,
            email
        );
        assertThat(passwordService.matchesAuthenticationCandidate(replacementPassword, passwordHash)).isTrue();
        assertThat(passwordService.matchesAuthenticationCandidate(VALID_PASSWORD, passwordHash)).isFalse();
        assertThat(jdbc.queryForObject(
            "SELECT token_version FROM users WHERE email = ?",
            Integer.class,
            email
        )).isOne();
        assertThat(activeResetTokens(email)).isZero();
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*)
            FROM refresh_tokens r JOIN users u ON u.id = r.user_id
            WHERE u.email = ? AND r.revoked_at IS NULL
            """, Integer.class, email)).isZero();
        assertThat(auditCount("PASSWORD_RESET_COMPLETED")).isOne();

        List<String> cookies = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE);
        assertThat(cookies).anySatisfy(cookie -> assertThat(cookie)
            .contains("adept_refresh=", "Max-Age=0", "Path=/api/v1/auth", "HttpOnly"));
        assertThat(cookies).anySatisfy(cookie -> assertThat(cookie)
            .contains("XSRF-TOKEN=", "Max-Age=0", "Path=/"));
        CapturedMail changed = mailSender.await(
            message -> message.recipients().contains(email)
                && "Your Adept password changed".equals(message.subject()),
            java.time.Duration.ofSeconds(5)
        );
        assertThat(changed.body()).doesNotContain(rawToken, replacementPassword);
    }

    @Test
    void expiredWrongPurposeConsumedAndUnknownResetTokensUseOneSafeProblem() {
        String expiredEmail = uniqueEmail("reset-expired");
        signup(expiredEmail);
        String expired = issueReset(expiredEmail);
        jdbc.update("UPDATE user_action_tokens SET expires_at = CURRENT_TIMESTAMP WHERE token_hash = ?",
            actionTokenService.hash(ActionTokenPurpose.RESET_PASSWORD, expired));

        String consumedEmail = uniqueEmail("reset-consumed");
        signup(consumedEmail);
        String consumed = issueReset(consumedEmail);
        jdbc.update("UPDATE user_action_tokens SET consumed_at = CURRENT_TIMESTAMP WHERE token_hash = ?",
            actionTokenService.hash(ActionTokenPurpose.RESET_PASSWORD, consumed));

        String wrongPurposeEmail = uniqueEmail("reset-wrong-purpose");
        signup(wrongPurposeEmail);
        String verificationToken = awaitToken(wrongPurposeEmail, "Verify your Adept email");

        assertInvalidReset(expired);
        assertInvalidReset(consumed);
        assertInvalidReset(verificationToken);
        assertInvalidReset(new SecureTokenGenerator().generate());
        assertThat(auditCount("PASSWORD_RESET_COMPLETED")).isZero();
    }

    @Test
    void resetEnforcesCommonPasswordAndUtf8BoundaryWithoutConsumingRejectedToken() throws Exception {
        String commonEmail = uniqueEmail("reset-common");
        signup(commonEmail);
        String commonToken = issueReset(commonEmail);
        CsrfPair commonCsrf = fetchCsrf(mockMvc);
        mockMvc.perform(post("/api/v1/auth/reset-password")
                .cookie(commonCsrf.cookie())
                .header("X-XSRF-TOKEN", commonCsrf.token())
                .header(HttpHeaders.ORIGIN, FRONTEND_ORIGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(resetJson(commonToken, "masterbating")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertThat(activeResetTokens(commonEmail)).isOne();

        String oversizedEmail = uniqueEmail("reset-oversized");
        signup(oversizedEmail);
        String oversizedToken = issueReset(oversizedEmail);
        CsrfPair oversizedCsrf = fetchCsrf(mockMvc);
        mockMvc.perform(post("/api/v1/auth/reset-password")
                .cookie(oversizedCsrf.cookie())
                .header("X-XSRF-TOKEN", oversizedCsrf.token())
                .header(HttpHeaders.ORIGIN, FRONTEND_ORIGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(resetJson(oversizedToken, "x".repeat(73))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertThat(activeResetTokens(oversizedEmail)).isOne();

        String boundaryEmail = uniqueEmail("reset-boundary");
        signup(boundaryEmail);
        String boundaryToken = issueReset(boundaryEmail);
        String boundaryPassword = "x".repeat(71) + "Z";
        authService.resetPassword(boundaryToken, boundaryPassword, requestContext());
        String hash = jdbc.queryForObject(
            "SELECT password_hash FROM users WHERE email = ?",
            String.class,
            boundaryEmail
        );
        assertThat(passwordService.matchesAuthenticationCandidate(boundaryPassword, hash)).isTrue();
    }

    @Test
    void twoConcurrentUsesOfOneResetTokenAllowOneCommittedMutation() throws Exception {
        String email = uniqueEmail("reset-concurrent");
        signup(email);
        String rawToken = issueReset(email);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<String>> results = List.of(
                executor.submit(() -> concurrentReset(rawToken, "new-password-alpha-7531", ready, start)),
                executor.submit(() -> concurrentReset(rawToken, "new-password-bravo-8642", ready, start))
            );
            ready.await();
            start.countDown();
            assertThat(List.of(results.get(0).get(), results.get(1).get()))
                .containsExactlyInAnyOrder("SUCCESS", ProblemCode.ACTION_TOKEN_INVALID.name());
        } finally {
            executor.shutdownNow();
        }

        assertThat(activeResetTokens(email)).isZero();
        assertThat(jdbc.queryForObject(
            "SELECT token_version FROM users WHERE email = ?",
            Integer.class,
            email
        )).isOne();
        assertThat(auditCount("PASSWORD_RESET_COMPLETED")).isOne();
    }

    @Test
    void concurrentForgotRequestsLeaveOneUsableResetToken() throws Exception {
        String email = uniqueEmail("forgot-concurrent");
        signup(email);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> results = List.of(
                executor.submit(() -> concurrentForgot(email, ready, start)),
                executor.submit(() -> concurrentForgot(email, ready, start))
            );
            ready.await();
            start.countDown();
            results.get(0).get();
            results.get(1).get();
        } finally {
            executor.shutdownNow();
        }

        assertThat(activeResetTokens(email)).isOne();
        assertThat(auditCount("PASSWORD_RESET_REQUESTED")).isEqualTo(2);
    }

    @Test
    void forgotKnownAndUnknownResponsesAreIndistinguishable() throws Exception {
        String known = uniqueEmail("forgot-known");
        signup(known);
        String unknown = uniqueEmail("forgot-unknown");

        List<MvcResult> responses = List.of(forgotRequest(known), forgotRequest(unknown));

        assertThat(responses).allSatisfy(response -> {
            assertThat(response.getResponse().getStatus()).isEqualTo(202);
            assertThat(response.getResponse().getContentAsByteArray()).isEmpty();
            assertThat(response.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        });
    }

    private void signup(String email) {
        authService.signup(
            new SignupRequest(email, VALID_PASSWORD, "Asha", "Asha Workspace", "UTC"),
            requestContext()
        );
    }

    private String issueReset(String email) {
        authService.forgotPassword(email, requestContext());
        return awaitToken(email, RESET_SUBJECT);
    }

    private void addAnotherResetToken(String email) {
        String raw = new SecureTokenGenerator().generate();
        jdbc.update("""
            INSERT INTO user_action_tokens (user_id, purpose, token_hash, expires_at)
            SELECT id, 'RESET_PASSWORD', ?, CURRENT_TIMESTAMP + INTERVAL '1 hour'
            FROM users WHERE email = ?
            """, actionTokenService.hash(ActionTokenPurpose.RESET_PASSWORD, raw), email);
    }

    private void addRefreshTokens(String email, int count) {
        for (int index = 0; index < count; index++) {
            jdbc.update("""
                INSERT INTO refresh_tokens (user_id, family_id, token_hash, expires_at)
                SELECT id, ?, ?, CURRENT_TIMESTAMP + INTERVAL '7 days'
                FROM users WHERE email = ?
                """, UUID.randomUUID(), "refresh-hash-" + UUID.randomUUID(), email);
        }
    }

    private MvcResult forgotRequest(String email) throws Exception {
        CsrfPair csrf = fetchCsrf(mockMvc);
        return mockMvc.perform(post("/api/v1/auth/forgot-password")
                .cookie(csrf.cookie())
                .header("X-XSRF-TOKEN", csrf.token())
                .header(HttpHeaders.ORIGIN, FRONTEND_ORIGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\"}"))
            .andExpect(status().isAccepted())
            .andReturn();
    }

    private String concurrentReset(
            String rawToken,
            String newPassword,
            CountDownLatch ready,
            CountDownLatch start) throws InterruptedException {
        ready.countDown();
        start.await();
        try {
            authService.resetPassword(rawToken, newPassword, requestContext());
            return "SUCCESS";
        } catch (ApiException exception) {
            return exception.code().name();
        }
    }

    private void concurrentForgot(
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
        authService.forgotPassword(email, requestContext());
    }

    private void assertInvalidReset(String rawToken) {
        assertThatThrownBy(() -> authService.resetPassword(
                rawToken,
                "replacement-password-9753",
                requestContext()
            ))
            .isInstanceOfSatisfying(ApiException.class,
                exception -> assertThat(exception.code()).isEqualTo(ProblemCode.ACTION_TOKEN_INVALID));
    }

    private int activeResetTokens(String email) {
        return jdbc.queryForObject("""
            SELECT COUNT(*)
            FROM user_action_tokens t JOIN users u ON u.id = t.user_id
            WHERE u.email = ? AND t.purpose = 'RESET_PASSWORD' AND t.consumed_at IS NULL
            """, Integer.class, email);
    }

    private int auditCount(String action) {
        return jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_logs WHERE action = ?",
            Integer.class,
            action
        );
    }

    private static String resetJson(String rawToken, String newPassword) {
        return """
            {"token":"%s","newPassword":"%s"}
            """.formatted(rawToken, newPassword);
    }
}
