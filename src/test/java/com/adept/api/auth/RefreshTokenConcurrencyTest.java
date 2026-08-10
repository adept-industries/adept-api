package com.adept.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.adept.api.auth.dto.SignupRequest;
import com.adept.api.auth.dto.SignupResponse;

import jakarta.servlet.http.Cookie;

class RefreshTokenConcurrencyTest extends PartCIntegrationTestSupport {

    @Autowired
    private AuthService authService;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void concurrentRefreshRaceConditionTriggersReuseDetectionAndRevokesFamily() throws Exception {
        String email = uniqueEmail("concurrency-race");
        SignupResponse signup = authService.signup(
            new SignupRequest(email, VALID_PASSWORD, "Concurrent User", "Concurrent Workspace", "UTC"),
            requestContext()
        );

        jdbc.update("UPDATE users SET email_verified_at = now() WHERE id = ?", signup.user().id());

        CsrfPair csrf = fetchCsrf(mockMvc);
        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                .header("Origin", FRONTEND_ORIGIN)
                .header("X-XSRF-TOKEN", csrf.token())
                .cookie(csrf.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "email": "%s",
                        "password": "%s"
                    }
                    """.formatted(email, VALID_PASSWORD)))
            .andExpect(status().isOk())
            .andReturn();

        Cookie parentCookie = loginResult.getResponse().getCookie("adept_refresh");
        assertThat(parentCookie).isNotNull();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        CompletableFuture<MvcResult> threadA = CompletableFuture.supplyAsync(() -> {
            try {
                startLatch.await();
                CsrfPair csrfA = fetchCsrf(mockMvc);
                return mockMvc.perform(post("/api/v1/auth/refresh")
                        .header("Origin", FRONTEND_ORIGIN)
                        .header("X-XSRF-TOKEN", csrfA.token())
                        .cookie(csrfA.cookie(), parentCookie)
                        .contentType(MediaType.APPLICATION_JSON))
                    .andReturn();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, executor);

        CompletableFuture<MvcResult> threadB = CompletableFuture.supplyAsync(() -> {
            try {
                startLatch.await();
                CsrfPair csrfB = fetchCsrf(mockMvc);
                return mockMvc.perform(post("/api/v1/auth/refresh")
                        .header("Origin", FRONTEND_ORIGIN)
                        .header("X-XSRF-TOKEN", csrfB.token())
                        .cookie(csrfB.cookie(), parentCookie)
                        .contentType(MediaType.APPLICATION_JSON))
                    .andReturn();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, executor);

        // Start both threads simultaneously
        startLatch.countDown();

        MvcResult resultA = threadA.get(10, TimeUnit.SECONDS);
        MvcResult resultB = threadB.get(10, TimeUnit.SECONDS);
        executor.shutdown();

        int statusA = resultA.getResponse().getStatus();
        int statusB = resultB.getResponse().getStatus();

        // Exactly one thread must succeed (200 OK) and the other must trigger reuse detection (401 Unauthorized)
        assertThat(List.of(statusA, statusB))
            .containsExactlyInAnyOrder(200, 401);

        Integer tokenVersion = jdbc.queryForObject(
            "SELECT token_version FROM users WHERE id = ?",
            Integer.class,
            signup.user().id()
        );
        assertThat(tokenVersion).isGreaterThan(0);

        Integer unrevokedCount = jdbc.queryForObject(
            "SELECT count(*) FROM refresh_tokens WHERE user_id = ? AND revoked_at IS NULL",
            Integer.class,
            signup.user().id()
        );
        assertThat(unrevokedCount).isEqualTo(0);
    }
}
