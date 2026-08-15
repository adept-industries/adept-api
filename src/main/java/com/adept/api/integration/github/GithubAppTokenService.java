package com.adept.api.integration.github;

import java.security.PrivateKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.adept.api.common.error.ApiException;
import com.adept.api.common.error.ProblemCode;
import com.adept.api.config.AppProperties;
import com.adept.api.crypto.RsaKeyUtils;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import io.jsonwebtoken.Jwts;

@ConditionalOnProperty(name = "app.github.enabled", havingValue = "true")
@Service
public class GithubAppTokenService {

    private static final String GITHUB_API_BASE_URL = "https://api.github.com";

    private final AppProperties properties;
    private final Clock clock;
    private final RestClient restClient;
    private final Cache<Long, String> tokenCache;

    public GithubAppTokenService(AppProperties properties, Clock clock, RestClient.Builder restClientBuilder) {
        this.properties = properties;
        this.clock = clock;
        this.restClient = restClientBuilder
            .baseUrl(GITHUB_API_BASE_URL)
            .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
            .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
            .build();
        this.tokenCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(50))
            .maximumSize(1000)
            .build();
    }

    public String generateAppJwt() {
        if (!properties.github().enabled()) {
            throw new ApiException(ProblemCode.INTEGRATION_DISABLED, "GitHub integration is disabled");
        }

        PrivateKey privateKey = RsaKeyUtils.parsePrivateKey(properties.github().privateKeyBase64());
        Instant now = clock.instant();
        Instant issuedAt = now.minusSeconds(60);
        Instant expiresAt = now.plus(Duration.ofMinutes(9));

        return Jwts.builder()
            .issuer(properties.github().appId())
            .issuedAt(Date.from(issuedAt))
            .expiration(Date.from(expiresAt))
            .signWith(privateKey, Jwts.SIG.RS256)
            .compact();
    }

    public String getInstallationToken(long installationId) {
        String cachedToken = tokenCache.getIfPresent(installationId);
        if (cachedToken != null) {
            return cachedToken;
        }

        String appJwt = generateAppJwt();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.post()
                .uri("/app/installations/{installation_id}/access_tokens", installationId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + appJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(Map.class);

            if (response == null || !response.containsKey("token")) {
                throw new ApiException(ProblemCode.INTEGRATION_PROVIDER_ERROR, "GitHub did not return an installation token");
            }

            String token = (String) response.get("token");
            tokenCache.put(installationId, token);
            return token;
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ApiException(
                ProblemCode.INTEGRATION_PROVIDER_ERROR,
                "Failed to retrieve GitHub installation access token: " + exception.getMessage()
            );
        }
    }

    public void evictInstallationToken(long installationId) {
        tokenCache.invalidate(installationId);
    }
}
