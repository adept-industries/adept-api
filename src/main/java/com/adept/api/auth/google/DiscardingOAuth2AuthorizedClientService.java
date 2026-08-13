package com.adept.api.auth.google;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;

/**
 * Google is used only for OIDC authentication, so provider access tokens must
 * not outlive the callback request.
 */
final class DiscardingOAuth2AuthorizedClientService implements OAuth2AuthorizedClientService {

    @Override
    public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(
            String clientRegistrationId,
            String principalName) {
        return null;
    }

    @Override
    public void saveAuthorizedClient(
            OAuth2AuthorizedClient authorizedClient,
            Authentication principal) {
        // Intentionally discarded: Adept does not call Google APIs after login.
    }

    @Override
    public void removeAuthorizedClient(String clientRegistrationId, String principalName) {
        // Nothing is retained.
    }
}
