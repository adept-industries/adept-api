package com.adept.api.common.error;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;

import com.adept.api.common.web.TraceIdFilter;
import com.adept.api.security.ratelimit.RateLimitException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(new ProblemResponseFactory());

    @Test
    void failuresUseStableProblemsWithoutLeakingInternalDetails() {
        MockHttpServletRequest request = request("/api/v1/auth/login?secret=value");

        var response = handler.handleRateLimit(new RateLimitException(17), request);

        assertThat(response.getStatusCode().value()).isEqualTo(429);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("17");
        assertThat(response.getHeaders().getFirst(TraceIdFilter.TRACE_ID_HEADER)).isNotBlank();
        assertThat(response.getBody().getProperties()).containsEntry("code", "RATE_LIMITED");
        assertThat(response.getBody().getInstance().toString()).isEqualTo("/api/v1/auth/login");
        assertThat(response.getBody().toString()).doesNotContain("secret", "value");

        MockHttpServletRequest signupRequest = request("/api/v1/auth/signup");
        DataIntegrityViolationException exception = new DataIntegrityViolationException(
            "SQL containing submitted@example.com",
            new RuntimeException("duplicate constraint uq_users_email_lower submitted@example.com"));

        var conflictResponse = handler.handleDatabaseConflict(exception, signupRequest);

        assertThat(conflictResponse.getStatusCode().value()).isEqualTo(409);
        assertThat(conflictResponse.getBody().getProperties()).containsEntry("code", "EMAIL_ALREADY_EXISTS");
        assertThat(conflictResponse.getBody().toString())
            .doesNotContain("uq_users_email_lower", "submitted@example.com", "SQL");

        var unexpectedResponse = handler.handleUnexpected(
            new IllegalStateException("database password is hunter2"),
            request("/api/v1/workspaces/current"));

        assertThat(unexpectedResponse.getStatusCode().value()).isEqualTo(500);
        assertThat(unexpectedResponse.getBody().getProperties()).containsEntry("code", "INTERNAL_ERROR");
        assertThat(unexpectedResponse.getBody().toString()).doesNotContain("hunter2", "IllegalStateException");
    }

    private static MockHttpServletRequest request(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        int query = uri.indexOf('?');
        request.setRequestURI(query < 0 ? uri : uri.substring(0, query));
        if (query >= 0) {
            request.setQueryString(uri.substring(query + 1));
        }
        return request;
    }
}
