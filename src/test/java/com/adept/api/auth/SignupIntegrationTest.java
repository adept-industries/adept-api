package com.adept.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.adept.api.auth.dto.SignupRequest;
import com.adept.api.common.error.ApiException;
import com.adept.api.common.error.ProblemCode;
import com.adept.api.crypto.PasswordService;
import com.adept.api.workspace.WorkspaceRepository;
import com.adept.api.workspace.WorkspaceSlugService;

@Import(SignupIntegrationTest.ControlledSlugConfiguration.class)
class SignupIntegrationTest extends PartCIntegrationTestSupport {

    @Autowired
    private AuthService authService;

    @Autowired
    private PasswordService passwordService;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ControlledSlugService controlledSlugService;

    @BeforeEach
    void resetSlugSequence() {
        controlledSlugService.reset();
    }

    @Test
    void signupCreatesExactlyOneSafeAtomicGraph() throws Exception {
        String email = uniqueEmail("signup-graph");
        CsrfPair csrf = fetchCsrf(mockMvc);
        String response = mockMvc.perform(post("/api/v1/auth/signup")
                .cookie(csrf.cookie())
                .header("X-XSRF-TOKEN", csrf.token())
                .header(HttpHeaders.ORIGIN, FRONTEND_ORIGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(signupJson(email, VALID_PASSWORD, "Platform Team")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.user.email").value(email))
            .andExpect(jsonPath("$.user.emailVerified").value(false))
            .andExpect(jsonPath("$.workspace.role").value("MANAGER"))
            .andExpect(jsonPath("$.emailVerificationRequired").value(true))
            .andReturn().getResponse().getContentAsString();

        String passwordHash = jdbc.queryForObject(
            "SELECT password_hash FROM users WHERE email = ?",
            String.class,
            email
        );
        assertThat(passwordService.matchesAuthenticationCandidate(VALID_PASSWORD, passwordHash)).isTrue();
        assertThat(passwordHash).startsWith("$2").contains("$04$").doesNotContain(VALID_PASSWORD);
        assertThat(response).doesNotContain("passwordHash", VALID_PASSWORD);
        assertThat(count("users")).isOne();
        assertThat(count("workspaces")).isOne();
        assertThat(count("memberships")).isOne();
        assertThat(count("user_action_tokens")).isOne();
        assertThat(count("audit_logs")).isOne();
        assertThat(jdbc.queryForObject("SELECT role FROM memberships", String.class)).isEqualTo("MANAGER");
        assertThat(jdbc.queryForObject("SELECT purpose FROM user_action_tokens", String.class))
            .isEqualTo("VERIFY_EMAIL");
        assertThat(jdbc.queryForObject("SELECT metadata::text FROM audit_logs", String.class))
            .doesNotContain(email, VALID_PASSWORD, "token");
    }

    @Test
    void concurrentCaseInsensitiveDuplicateProducesOneSuccessAndOneConflict() throws Exception {
        String local = "Concurrent-" + java.util.UUID.randomUUID();
        SignupRequest first = request(local + "@Example.com", "First Workspace");
        SignupRequest second = request(local.toLowerCase(Locale.ROOT) + "@example.COM", "Second Workspace");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<String>> results = List.of(
                executor.submit(() -> concurrentSignup(first, ready, start)),
                executor.submit(() -> concurrentSignup(second, ready, start))
            );
            ready.await();
            start.countDown();

            assertThat(List.of(results.get(0).get(), results.get(1).get()))
                .containsExactlyInAnyOrder("SUCCESS", ProblemCode.EMAIL_ALREADY_EXISTS.name());
        } finally {
            executor.shutdownNow();
        }
        assertSuccessfulGraphCounts();
    }

    @Test
    void laterInsertFailureRollsBackTheWholeSignupGraph() {
        String email = uniqueEmail("signup-rollback");
        SignupRequest invalidWorkspace = request(email, "x".repeat(161));

        assertThatThrownBy(() -> authService.signup(invalidWorkspace, requestContext()))
            .isInstanceOfSatisfying(ApiException.class,
                exception -> assertThat(exception.code()).isEqualTo(ProblemCode.WORKSPACE_CONFLICT));

        assertThat(count("users")).isZero();
        assertThat(count("workspaces")).isZero();
        assertThat(count("memberships")).isZero();
        assertThat(count("user_action_tokens")).isZero();
        assertThat(count("audit_logs")).isZero();
    }

    @Test
    void knownSlugConstraintCollisionRetriesInAFreshTransaction() {
        jdbc.update("""
            INSERT INTO workspaces (name, slug, timezone, status)
            VALUES ('Existing', ?, 'UTC', 'ACTIVE')
            """, ControlledSlugService.FIRST_SLUG);
        String email = uniqueEmail("slug-retry");

        authService.signup(request(email, "Recovered Workspace"), requestContext());

        assertThat(controlledSlugService.attempts()).isEqualTo(2);
        assertThat(count("users")).isOne();
        assertThat(count("workspaces")).isEqualTo(2);
        assertThat(count("memberships")).isOne();
        assertThat(count("user_action_tokens")).isOne();
        assertThat(count("audit_logs")).isOne();
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM workspaces WHERE slug = ?",
            Integer.class,
            ControlledSlugService.SECOND_SLUG
        )).isOne();
    }

    @Test
    void smtpFailureDoesNotRollBackSignupAndResendProvidesRecovery() {
        String email = uniqueEmail("signup-mail-recovery");
        mailSender.failSending(true);

        authService.signup(request(email, "Mail Recovery Workspace"), requestContext());
        mailSender.awaitAttempts(1, Duration.ofSeconds(5));

        assertSuccessfulGraphCounts();
        mailSender.failSending(false);
        authService.resendVerification(email, requestContext());
        String replacement = awaitToken(email, "Verify your Adept email");
        assertThat(replacement).matches("^[A-Za-z0-9_-]{43}$");
        assertThat(count("user_action_tokens")).isEqualTo(2);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM user_action_tokens WHERE consumed_at IS NULL",
            Integer.class
        )).isOne();
    }

    @Test
    void workspaceSlugUsesCleanBaseRandomSuffixAndLengthLimit() {
        WorkspaceRepository repository = (WorkspaceRepository) Proxy.newProxyInstance(
            WorkspaceRepository.class.getClassLoader(),
            new Class<?>[] { WorkspaceRepository.class },
            (proxy, method, args) -> method.getName().equals("existsBySlug") ? false : null
        );
        WorkspaceSlugService service = new WorkspaceSlugService(repository);

        String slug = service.generate("  Adept Phase 2: Auth & Workspace Launch!!!  ");

        assertThat(slug)
            .startsWith("adept-phase-2-auth-workspace-launch-")
            .matches("^[a-z0-9-]+-[0-9a-f]{8}$")
            .hasSizeLessThanOrEqualTo(80);
    }

    private String concurrentSignup(
            SignupRequest request,
            CountDownLatch ready,
            CountDownLatch start) throws InterruptedException {
        ready.countDown();
        start.await();
        try {
            authService.signup(request, requestContext());
            return "SUCCESS";
        } catch (ApiException exception) {
            return exception.code().name();
        }
    }

    private void assertSuccessfulGraphCounts() {
        assertThat(count("users")).isOne();
        assertThat(count("workspaces")).isOne();
        assertThat(count("memberships")).isOne();
        assertThat(count("user_action_tokens")).isOne();
        assertThat(count("audit_logs")).isOne();
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private static SignupRequest request(String email, String workspaceName) {
        return new SignupRequest(email, VALID_PASSWORD, "Asha Perera", workspaceName, "UTC");
    }

    private static String signupJson(String email, String password, String workspaceName) {
        return """
            {
              "email": "%s",
              "password": "%s",
              "displayName": "Asha Perera",
              "workspaceName": "%s",
              "timezone": "UTC"
            }
            """.formatted(email, password, workspaceName);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ControlledSlugConfiguration {

        @Bean
        @Primary
        ControlledSlugService controlledSlugService(WorkspaceRepository repository) {
            return new ControlledSlugService(repository);
        }
    }

    static final class ControlledSlugService extends WorkspaceSlugService {

        static final String FIRST_SLUG = "forced-collision-00000001";
        static final String SECOND_SLUG = "forced-recovery-00000002";
        private final AtomicInteger sequence = new AtomicInteger();

        ControlledSlugService(WorkspaceRepository repository) {
            super(repository);
        }

        @Override
        public String generate(String workspaceName) {
            int attempt = sequence.incrementAndGet();
            return attempt == 1 ? FIRST_SLUG : SECOND_SLUG;
        }

        void reset() {
            sequence.set(0);
        }

        int attempts() {
            return sequence.get();
        }
    }
}
