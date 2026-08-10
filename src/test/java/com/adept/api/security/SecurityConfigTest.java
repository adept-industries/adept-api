package com.adept.api.security;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.adept.api.common.error.GlobalExceptionHandler;
import com.adept.api.common.error.ProblemResponseFactory;
import com.adept.api.common.error.ProblemWriter;
import com.adept.api.common.web.RequestBodyLimitFilter;
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
    TokenHasher.class,
    AuthRateLimiter.class
})
class SecurityConfigTest {

    private static final Pattern CSRF_COOKIE = Pattern.compile("XSRF-TOKEN=([^;]+)");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SecurityTestEndpoints endpoints;

    @Autowired
    private ApplicationContext context;

    @BeforeEach
    void resetEndpointCount() {
        endpoints.reset();
    }

    @Test
    void healthTreeIsPublicWhileOtherActuatorAndUnlistedRoutesAreDenied() throws Exception {
        mockMvc.perform(get("/actuator/health/readiness"))
            .andExpect(status().isNoContent());

        mockMvc.perform(get("/actuator/info"))
            .andExpect(status().isUnauthorized())
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));

        mockMvc.perform(get("/api/v1/unlisted"))
            .andExpect(status().isUnauthorized())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("SESSION_INVALID")));

        mockMvc.perform(get("/api/v1/auth/me"))
            .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/workspaces/current"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void malformedUnsupportedAndOversizedBodiesNeverInvokeController() throws Exception {
        String token = csrfToken();

        mockMvc.perform(post("/api/v1/auth/test-endpoint")
                .cookie(new Cookie("XSRF-TOKEN", token))
                .header("X-XSRF-TOKEN", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{"))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("MALFORMED_REQUEST")));

        mockMvc.perform(post("/api/v1/auth/test-endpoint")
                .cookie(new Cookie("XSRF-TOKEN", token))
                .header("X-XSRF-TOKEN", token)
                .contentType(MediaType.TEXT_PLAIN)
                .content("rejected-secret"))
            .andExpect(status().isUnsupportedMediaType())
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(content().string(org.hamcrest.Matchers.not(
                org.hamcrest.Matchers.containsString("rejected-secret"))));

        mockMvc.perform(post("/api/v1/auth/test-endpoint")
                .cookie(new Cookie("XSRF-TOKEN", token))
                .header("X-XSRF-TOKEN", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("x".repeat(RequestBodyLimitFilter.MAX_BODY_BYTES + 1)))
            .andExpect(status().isContentTooLarge())
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("PAYLOAD_TOO_LARGE")));

        assertThat(endpoints.invocations()).isZero();
    }

    @Test
    void filtersRunOnceAndOneRequestConsumesOnePeerAttempt() throws Exception {
        String token = csrfToken();

        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(post("/api/v1/auth/test-endpoint")
                    .with(request -> {
                        request.setRemoteAddr("192.0.2.44");
                        return request;
                    })
                    .cookie(new Cookie("XSRF-TOKEN", token))
                    .header("X-XSRF-TOKEN", token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"value\":\"ok\"}"))
                .andExpect(status().isNoContent());
        }

        mockMvc.perform(post("/api/v1/auth/test-endpoint")
                .with(request -> {
                    request.setRemoteAddr("192.0.2.44");
                    return request;
                })
                .cookie(new Cookie("XSRF-TOKEN", token))
                .header("X-XSRF-TOKEN", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"ok\"}"))
            .andExpect(status().isTooManyRequests())
            .andExpect(header().string(HttpHeaders.RETRY_AFTER, "900"));

        assertThat(endpoints.invocations()).isEqualTo(2);
    }

    @Test
    void allSecurityFiltersHaveServletAutoRegistrationDisabled() {
        Map<String, FilterRegistrationBean> registrations = context.getBeansOfType(FilterRegistrationBean.class);

        assertThat(registrations).hasSize(4);
        assertThat(registrations.values()).allMatch(registration -> !registration.isEnabled());
    }

    private String csrfToken() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/auth/csrf"))
            .andExpect(status().isNoContent())
            .andReturn();
        Matcher matcher = CSRF_COOKIE.matcher(result.getResponse().getHeader(HttpHeaders.SET_COOKIE));
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }
}
