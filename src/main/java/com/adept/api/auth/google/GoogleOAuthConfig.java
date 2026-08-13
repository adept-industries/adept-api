package com.adept.api.auth.google;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;

import com.adept.api.common.error.ProblemWriter;
import com.adept.api.common.web.TraceIdFilter;
import com.adept.api.config.AppProperties;
import com.adept.api.config.GoogleAuthProperties;
import com.adept.api.security.CsrfCookieService;
import com.adept.api.security.RefreshCookieService;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "app.google-auth", name = "enabled", havingValue = "true")
public class GoogleOAuthConfig {

    public static final String REGISTRATION_ID = "google";
    public static final String AUTHORIZATION_BASE_URI = "/api/v1/auth/google/authorization";
    public static final String CALLBACK_BASE_URI = "/api/v1/auth/google/callback";

    @Bean
    ClientRegistrationRepository googleClientRegistrationRepository(GoogleAuthProperties properties) {
        ClientRegistration registration = CommonOAuth2Provider.GOOGLE
            .getBuilder(REGISTRATION_ID)
            .clientId(properties.clientId())
            .clientSecret(properties.clientSecret())
            .scope("openid", "email", "profile")
            .redirectUri(properties.redirectUri().toString())
            .clientSettings(ClientRegistration.ClientSettings.builder()
                .requireProofKey(true)
                .build())
            .build();
        return new InMemoryClientRegistrationRepository(registration);
    }

    @Bean
    OAuth2AuthorizedClientService googleAuthorizedClientService() {
        return new DiscardingOAuth2AuthorizedClientService();
    }

    @Bean
    GoogleOAuthSuccessHandler googleOAuthSuccessHandler(
            GoogleAuthService googleAuthService,
            GoogleOAuthSessionService oauthSessionService,
            RefreshCookieService refreshCookieService,
            CsrfCookieService csrfCookieService,
            AppProperties appProperties) {
        return new GoogleOAuthSuccessHandler(
            googleAuthService,
            oauthSessionService,
            refreshCookieService,
            csrfCookieService,
            appProperties
        );
    }

    @Bean
    GoogleOAuthFailureHandler googleOAuthFailureHandler(
            GoogleOAuthSessionService oauthSessionService,
            CsrfCookieService csrfCookieService,
            AppProperties appProperties) {
        return new GoogleOAuthFailureHandler(oauthSessionService, csrfCookieService, appProperties);
    }

    @Bean
    @Order(1)
    SecurityFilterChain googleOAuthSecurityFilterChain(
            HttpSecurity http,
            ClientRegistrationRepository registrations,
            OAuth2AuthorizedClientService authorizedClientService,
            GoogleOAuthSuccessHandler successHandler,
            GoogleOAuthFailureHandler failureHandler,
            GoogleOAuthSessionService oauthSessionService,
            ProblemWriter problemWriter,
            TraceIdFilter traceIdFilter) throws Exception {
        GoogleOAuthStartGuardFilter startGuard = new GoogleOAuthStartGuardFilter(
            oauthSessionService,
            problemWriter,
            AUTHORIZATION_BASE_URI + "/" + REGISTRATION_ID
        );

        return http
            .securityMatcher(AUTHORIZATION_BASE_URI + "/**", CALLBACK_BASE_URI + "/**")
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
            .securityContext(context -> context
                .securityContextRepository(new RequestAttributeSecurityContextRepository()))
            .requestCache(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            .cors(AbstractHttpConfigurer::disable)
            .csrf(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
            .oauth2Login(oauth -> oauth
                .clientRegistrationRepository(registrations)
                .authorizedClientService(authorizedClientService)
                .authorizationEndpoint(endpoint -> endpoint.baseUri(AUTHORIZATION_BASE_URI))
                .redirectionEndpoint(endpoint -> endpoint.baseUri(CALLBACK_BASE_URI + "/*"))
                .successHandler(successHandler)
                .failureHandler(failureHandler))
            .addFilterBefore(traceIdFilter, OAuth2AuthorizationRequestRedirectFilter.class)
            .addFilterAfter(startGuard, TraceIdFilter.class)
            .build();
    }
}
