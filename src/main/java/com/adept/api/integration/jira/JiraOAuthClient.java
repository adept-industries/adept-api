package com.adept.api.integration.jira;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import com.adept.api.common.error.ApiException;
import com.adept.api.common.error.ProblemCode;
import com.adept.api.config.AppProperties;

@ConditionalOnProperty(name = "app.jira.enabled", havingValue = "true")
@Service
public class JiraOAuthClient {

    private static final String ATLASSIAN_AUTH_BASE_URL = "https://auth.atlassian.com";
    private static final String ATLASSIAN_API_BASE_URL = "https://api.atlassian.com";
    private static final String JIRA_SCOPES = "read:jira-work read:jira-user manage:jira-webhook offline_access";

    private final AppProperties properties;
    private final RestClient authRestClient;
    private final RestClient apiRestClient;

    public JiraOAuthClient(AppProperties properties, RestClient.Builder restClientBuilder) {
        this.properties = properties;
        this.authRestClient = restClientBuilder
            .baseUrl(ATLASSIAN_AUTH_BASE_URL)
            .build();
        this.apiRestClient = restClientBuilder
            .baseUrl(ATLASSIAN_API_BASE_URL)
            .build();
    }

    public String buildAuthorizationUrl(String rawState, String codeChallenge) {
        if (!properties.jira().enabled()) {
            throw new ApiException(ProblemCode.INTEGRATION_DISABLED, "Jira integration is not enabled");
        }

        return UriComponentsBuilder.fromUriString("https://auth.atlassian.com/authorize")
            .queryParam("audience", "api.atlassian.com")
            .queryParam("client_id", properties.jira().clientId())
            .queryParam("scope", JIRA_SCOPES)
            .queryParam("redirect_uri", properties.jira().callbackUrl().toString())
            .queryParam("state", rawState)
            .queryParam("response_type", "code")
            .queryParam("prompt", "consent")
            .queryParam("code_challenge", codeChallenge)
            .queryParam("code_challenge_method", "S256")
            .build()
            .toUriString();
    }

    public static String generateCodeChallenge(String codeVerifier) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public JiraTokenResponse exchangeCode(String code, String codeVerifier) {
        try {
            Map<String, String> payload = Map.of(
                "grant_type", "authorization_code",
                "client_id", properties.jira().clientId(),
                "client_secret", properties.jira().clientSecret(),
                "code", code,
                "redirect_uri", properties.jira().callbackUrl().toString(),
                "code_verifier", codeVerifier
            );

            @SuppressWarnings("unchecked")
            Map<String, Object> response = authRestClient.post()
                .uri("/oauth/token")
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .body(Map.class);

            if (response == null || !response.containsKey("access_token")) {
                throw new ApiException(ProblemCode.INTEGRATION_PROVIDER_ERROR, "Atlassian did not return access tokens");
            }

            String accessToken = (String) response.get("access_token");
            String refreshToken = (String) response.get("refresh_token");
            long expiresIn = ((Number) response.getOrDefault("expires_in", 3600)).longValue();
            String scope = (String) response.getOrDefault("scope", JIRA_SCOPES);

            return new JiraTokenResponse(accessToken, refreshToken, expiresIn, scope.split("\\s+"));
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ApiException(
                ProblemCode.INTEGRATION_PROVIDER_ERROR,
                "Failed to exchange Jira authorization code: " + exception.getMessage()
            );
        }
    }

    public JiraTokenResponse refreshToken(String currentRefreshToken) {
        try {
            Map<String, String> payload = Map.of(
                "grant_type", "refresh_token",
                "client_id", properties.jira().clientId(),
                "client_secret", properties.jira().clientSecret(),
                "refresh_token", currentRefreshToken
            );

            @SuppressWarnings("unchecked")
            Map<String, Object> response = authRestClient.post()
                .uri("/oauth/token")
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .body(Map.class);

            if (response == null || !response.containsKey("access_token")) {
                throw new ApiException(ProblemCode.INTEGRATION_PROVIDER_ERROR, "Atlassian token refresh failed");
            }

            String accessToken = (String) response.get("access_token");
            String newRefreshToken = (String) response.getOrDefault("refresh_token", currentRefreshToken);
            long expiresIn = ((Number) response.getOrDefault("expires_in", 3600)).longValue();
            String scope = (String) response.getOrDefault("scope", JIRA_SCOPES);

            return new JiraTokenResponse(accessToken, newRefreshToken, expiresIn, scope.split("\\s+"));
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ApiException(
                ProblemCode.INTEGRATION_PROVIDER_ERROR,
                "Failed to refresh Jira access token: " + exception.getMessage()
            );
        }
    }

    public List<JiraAccessibleResource> getAccessibleResources(String accessToken) {
        try {
            List<Map<String, Object>> resources = apiRestClient.get()
                .uri("/oauth/token/accessible-resources")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .retrieve()
                .body(new ParameterizedTypeReference<List<Map<String, Object>>>() {});

            if (resources == null || resources.isEmpty()) {
                throw new ApiException(ProblemCode.INTEGRATION_PROVIDER_ERROR, "No accessible Jira Cloud sites found");
            }

            return resources.stream().map(r -> new JiraAccessibleResource(
                (String) r.get("id"),
                (String) r.get("url"),
                (String) r.get("name"),
                (String) r.get("avatarUrl")
            )).toList();
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ApiException(
                ProblemCode.INTEGRATION_PROVIDER_ERROR,
                "Failed to fetch Jira accessible resources: " + exception.getMessage()
            );
        }
    }

    public record JiraTokenResponse(
        String accessToken,
        String refreshToken,
        long expiresInSeconds,
        String[] scopes
    ) {
    }

    public record JiraAccessibleResource(
        String id,
        String url,
        String name,
        String avatarUrl
    ) {
    }
}
