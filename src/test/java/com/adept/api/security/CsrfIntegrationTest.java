package com.adept.api.security;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.adept.api.common.error.GlobalExceptionHandler;
import com.adept.api.common.error.ProblemResponseFactory;
import com.adept.api.common.error.ProblemWriter;
import com.adept.api.config.SecurityConfig;
import com.adept.api.crypto.TokenHasher;
import com.adept.api.security.ratelimit.AuthRateLimiter;
import com.adept.api.support.SecuritySliceConfiguration;
import com.adept.api.support.SecurityTestEndpoints;

import jakarta.servlet.http.Cookie;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {CsrfController.class, SecurityTestEndpoints.class})
@Import({
    SecurityConfig.class,
    SecuritySliceConfiguration.class,
    ProblemResponseFactory.class,
    ProblemWriter.class,
    GlobalExceptionHandler.class,
    ApiAuthenticationEntryPoint.class,
    ApiAccessDeniedHandler.class,
    CsrfCookieService.class,
    TokenHasher.class,
    AuthRateLimiter.class
})
class CsrfIntegrationTest {

    private static final Pattern CSRF_COOKIE = Pattern.compile("(?:^|;\\s*)XSRF-TOKEN=([^;]+)");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CsrfCookieService csrfCookieService;

    @ParameterizedTest
    @ValueSource(strings = {
        "/api/v1/auth/signup",
        "/api/v1/auth/verify-email",
        "/api/v1/auth/resend-verification",
        "/api/v1/auth/login",
        "/api/v1/auth/refresh",
        "/api/v1/auth/logout",
        "/api/v1/auth/switch-workspace/10000000-0000-0000-0000-000000000001",
        "/api/v1/auth/forgot-password",
        "/api/v1/auth/reset-password"
    })
    void everyUnsafeAuthRouteRejectsMissingCsrf(String path) throws Exception {
        mockMvc.perform(post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isForbidden())
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("CSRF_INVALID")))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("no-store")));
    }

    @Test
    void csrfEndpointEmitsExactCookieAndPlainCookieHeaderPairPasses() throws Exception {
        CsrfPair pair = csrfPair();

        assertThat(pair.setCookie())
            .contains("XSRF-TOKEN=" + pair.token())
            .contains("Path=/")
            .contains("Secure")
            .doesNotContain("HttpOnly", "Domain=");
        assertThat(pair.cookie().getAttribute("SameSite")).isEqualTo("Strict");
        assertThat(pair.cookie().isHttpOnly()).isFalse();
        assertThat(pair.cookie().getDomain()).isNull();

        mockMvc.perform(post("/api/v1/auth/login")
                .cookie(new Cookie("XSRF-TOKEN", pair.token()))
                .header("X-XSRF-TOKEN", pair.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"ok\"}"))
            .andExpect(status().isNoContent())
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("no-store")));
    }

    @Test
    void wrongCsrfAndWrongOriginUseStableProblems() throws Exception {
        CsrfPair pair = csrfPair();

        mockMvc.perform(post("/api/v1/auth/login")
                .cookie(new Cookie("XSRF-TOKEN", pair.token()))
                .header("X-XSRF-TOKEN", "wrong")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isForbidden())
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("CSRF_INVALID")));

        mockMvc.perform(post("/api/v1/auth/login")
                .header(HttpHeaders.ORIGIN, "https://evil.example")
                .cookie(new Cookie("XSRF-TOKEN", pair.token()))
                .header("X-XSRF-TOKEN", pair.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isForbidden())
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("ORIGIN_INVALID")));
    }

    @Test
    void explicitExpiryUsesTheSameCsrfCookieScope() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        csrfCookieService.expire(new MockHttpServletRequest(), response);

        assertThat(response.getHeader(HttpHeaders.SET_COOKIE))
            .startsWith("XSRF-TOKEN=")
            .contains("Path=/")
            .contains("Max-Age=0")
            .contains("Secure")
            .doesNotContain("HttpOnly", "Domain=");
        assertThat(response.getCookie("XSRF-TOKEN").getAttribute("SameSite")).isEqualTo("Strict");
    }

    private CsrfPair csrfPair() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/auth/csrf"))
            .andExpect(status().isNoContent())
            .andReturn();
        String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        Matcher matcher = CSRF_COOKIE.matcher(setCookie);
        assertThat(matcher.find()).isTrue();
        return new CsrfPair(matcher.group(1), setCookie, result.getResponse().getCookie("XSRF-TOKEN"));
    }

    private record CsrfPair(String token, String setCookie, Cookie cookie) {
    }
}
