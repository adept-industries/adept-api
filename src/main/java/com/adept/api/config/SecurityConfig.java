package com.adept.api.config;

import java.time.Clock;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;

import com.adept.api.common.error.ProblemWriter;
import com.adept.api.common.web.RequestBodyLimitFilter;
import com.adept.api.common.web.TraceIdFilter;
import com.adept.api.security.ApiAccessDeniedHandler;
import com.adept.api.security.ApiAuthenticationEntryPoint;
import com.adept.api.security.JwtAuthenticationFilter;
import com.adept.api.security.OriginValidationFilter;
import com.adept.api.security.ratelimit.AuthPeerRateLimitFilter;
import com.adept.api.security.ratelimit.AuthRateLimiter;

@Configuration
@EnableMethodSecurity
@SuppressWarnings("unused")
public class SecurityConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    CookieCsrfTokenRepository csrfTokenRepository(AppProperties properties) {
        CookieCsrfTokenRepository repository = new CookieCsrfTokenRepository();
        repository.setCookieName("XSRF-TOKEN");
        repository.setHeaderName("X-XSRF-TOKEN");
        repository.setCookiePath("/");
        repository.setCookieCustomizer(cookie -> cookie
            .httpOnly(false)
            .secure(properties.refreshToken().cookieSecure())
            .sameSite("Strict")
            .path("/"));
        return repository;
    }

    @Bean
    TraceIdFilter traceIdFilter() {
        return new TraceIdFilter();
    }

    @Bean
    RequestBodyLimitFilter requestBodyLimitFilter(ProblemWriter problemWriter) {
        return new RequestBodyLimitFilter(problemWriter);
    }

    @Bean
    OriginValidationFilter originValidationFilter(
            AppProperties properties,
            ProblemWriter problemWriter) {
        return new OriginValidationFilter(properties, problemWriter);
    }

    @Bean
    AuthPeerRateLimitFilter authPeerRateLimitFilter(
            AuthRateLimiter rateLimiter,
            ProblemWriter problemWriter) {
        return new AuthPeerRateLimitFilter(rateLimiter, problemWriter);
    }

    @Bean
    FilterRegistrationBean<TraceIdFilter> disableTraceIdServletRegistration(TraceIdFilter filter) {
        return disabledRegistration(filter);
    }

    @Bean
    FilterRegistrationBean<RequestBodyLimitFilter> disableBodyLimitServletRegistration(
            RequestBodyLimitFilter filter) {
        return disabledRegistration(filter);
    }

    @Bean
    FilterRegistrationBean<OriginValidationFilter> disableOriginServletRegistration(
            OriginValidationFilter filter) {
        return disabledRegistration(filter);
    }

    @Bean
    FilterRegistrationBean<AuthPeerRateLimitFilter> disablePeerLimitServletRegistration(
            AuthPeerRateLimitFilter filter) {
        return disabledRegistration(filter);
    }

    @Bean
    FilterRegistrationBean<JwtAuthenticationFilter> disableJwtServletRegistration(
            JwtAuthenticationFilter filter) {
        return disabledRegistration(filter);
    }

    @Bean
    @Order(2)
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            CookieCsrfTokenRepository csrfTokenRepository,
            ApiAuthenticationEntryPoint authenticationEntryPoint,
            ApiAccessDeniedHandler accessDeniedHandler,
            TraceIdFilter traceIdFilter,
            RequestBodyLimitFilter bodyLimitFilter,
            OriginValidationFilter originValidationFilter,
            AuthPeerRateLimitFilter peerRateLimitFilter,
            JwtAuthenticationFilter jwtAuthenticationFilter) throws Exception {
        return http
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .requestCache(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            .cors(AbstractHttpConfigurer::disable)
            .csrf(csrf -> {
                csrf.ignoringRequestMatchers("/api/v1/webhooks/**");
                csrf.spa();
                csrf.csrfTokenRepository(csrfTokenRepository);
            })
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint(authenticationEntryPoint)
                .accessDeniedHandler(accessDeniedHandler))
            .authorizeHttpRequests(authorize -> authorize
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                .requestMatchers("/actuator/**").denyAll()
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/auth/csrf").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/auth/google/start").permitAll()
                .requestMatchers(HttpMethod.GET,
                    "/api/v1/integrations/github/callback",
                    "/api/v1/integrations/jira/callback"
                ).permitAll()
                .requestMatchers(HttpMethod.POST,
                    "/api/v1/auth/signup",
                    "/api/v1/auth/verify-email",
                    "/api/v1/auth/resend-verification",
                    "/api/v1/auth/login",
                    "/api/v1/auth/test-endpoint",
                    "/api/v1/auth/refresh",
                    "/api/v1/auth/logout",
                    "/api/v1/auth/switch-workspace/*",
                    "/api/v1/auth/workspaces",
                    "/api/v1/auth/forgot-password",
                    "/api/v1/auth/reset-password",
                    "/api/v1/auth/google/onboarding",
                    "/api/v1/webhooks/github",
                    "/api/v1/webhooks/**"
                ).permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/auth/me", "/api/v1/auth/test-me").authenticated()
                .requestMatchers(HttpMethod.POST,
                    "/api/v1/auth/reauthenticate/password",
                    "/api/v1/auth/google/reauthentication/start"
                ).authenticated()
                .requestMatchers(HttpMethod.GET, "/api/v1/invitations/preview").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/invitations/accept").permitAll()
                .requestMatchers("/api/v1/invitations", "/api/v1/invitations/**").authenticated()
                .requestMatchers("/api/v1/workspaces", "/api/v1/workspaces/**").authenticated()
                .requestMatchers("/api/v1/projects", "/api/v1/projects/**").authenticated()
                .requestMatchers("/api/v1/integrations", "/api/v1/integrations/**").authenticated()
                .requestMatchers("/api/v1/repositories", "/api/v1/repositories/**").authenticated()
                .requestMatchers("/api/v1/jira", "/api/v1/jira/**").authenticated()
                .anyRequest().denyAll())
            .addFilterBefore(originValidationFilter, CsrfFilter.class)
            .addFilterBefore(bodyLimitFilter, OriginValidationFilter.class)
            .addFilterBefore(traceIdFilter, RequestBodyLimitFilter.class)
            .addFilterAfter(peerRateLimitFilter, CsrfFilter.class)
            .addFilterBefore(jwtAuthenticationFilter, AuthorizationFilter.class)
            .build();
    }

    private static <T extends jakarta.servlet.Filter> FilterRegistrationBean<T> disabledRegistration(T filter) {
        FilterRegistrationBean<T> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
