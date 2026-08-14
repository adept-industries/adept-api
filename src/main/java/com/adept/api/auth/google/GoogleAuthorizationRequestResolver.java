package com.adept.api.auth.google;

import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

import jakarta.servlet.http.HttpServletRequest;

final class GoogleAuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {

    private static final String AUTH_TIME_CLAIM =
        "{\"id_token\":{\"auth_time\":{\"essential\":true}}}";

    private final DefaultOAuth2AuthorizationRequestResolver delegate;
    private final GoogleOAuthSessionService sessionService;

    GoogleAuthorizationRequestResolver(
            ClientRegistrationRepository registrations,
            GoogleOAuthSessionService sessionService) {
        this.delegate = new DefaultOAuth2AuthorizationRequestResolver(
            registrations,
            GoogleOAuthConfig.AUTHORIZATION_BASE_URI
        );
        this.sessionService = sessionService;
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
        return customize(request, delegate.resolve(request));
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String registrationId) {
        return customize(request, delegate.resolve(request, registrationId));
    }

    private OAuth2AuthorizationRequest customize(
            HttpServletRequest request,
            OAuth2AuthorizationRequest authorizationRequest) {
        if (authorizationRequest == null
                || sessionService.pendingReauthentication(request).isEmpty()) {
            return authorizationRequest;
        }
        return OAuth2AuthorizationRequest.from(authorizationRequest)
            .additionalParameters(parameters -> {
                parameters.put("prompt", "select_account");
                parameters.put("max_age", 0);
                parameters.put("claims", AUTH_TIME_CLAIM);
            })
            .build();
    }
}

