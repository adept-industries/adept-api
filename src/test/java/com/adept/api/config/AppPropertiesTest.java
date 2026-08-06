package com.adept.api.config;

import java.net.URI;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class AppPropertiesTest {

    private final ApplicationContextRunner contextRunner = baseContextRunner()
        .withPropertyValues(validProperties());

    @Test
    void bindsValidConfiguration() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            AppProperties properties = context.getBean(AppProperties.class);
            assertThat(properties.jwt().issuer()).isEqualTo("adept-api");
            assertThat(properties.auth().bcryptCost()).isEqualTo(12);
            assertThat(properties.frontendBaseUrl()).isEqualTo(URI.create("http://localhost:3000/"));
            assertThat(properties.integrationEncryption().activeKeyVersion()).isEqualTo(1);
            assertThat(properties.github().enabled()).isFalse();
        });
    }

    @Test
    void rejectsInvalidCoreSecret() {
        contextRunner
            .withPropertyValues("app.jwt.secret-base64=not-base64")
            .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rejectsInvalidNonActiveIntegrationEncryptionKey() {
        contextRunner
            .withPropertyValues("app.integration-encryption.keys[2]=not-base64")
            .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rejectsEnabledGithubWithoutCredentials() {
        contextRunner
            .withPropertyValues(
                "app.github.enabled=true",
                "app.github.app-id=",
                "app.github.app-slug="
            )
            .run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(ints = {4, 12, 31})
    void acceptsLegalBcryptCosts(int cost) {
        contextRunner
            .withPropertyValues("app.auth.bcrypt-cost=" + cost)
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(AppProperties.class).auth().bcryptCost()).isEqualTo(cost);
            });
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 3, 32})
    void rejectsIllegalBcryptCosts(int cost) {
        contextRunner
            .withPropertyValues("app.auth.bcrypt-cost=" + cost)
            .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rejectsMissingBcryptCost() {
        baseContextRunner()
            .withPropertyValues(validPropertiesWithout("app.auth.bcrypt-cost"))
            .run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "app.jwt.access-token-ttl",
        "app.refresh-token.ttl",
        "app.auth.verification-token-ttl",
        "app.auth.reset-token-ttl",
        "app.auth.rate-limit.auth-peer-window",
        "app.auth.rate-limit.login-window",
        "app.auth.rate-limit.signup-window",
        "app.auth.rate-limit.account-email-window",
        "app.auth.rate-limit.action-token-window",
        "app.auth.rate-limit.deletion-user-window"
    })
    void rejectsZeroAndNegativeDurations(String propertyName) {
        for (String invalidValue : new String[] {"PT0S", "-PT1S"}) {
            contextRunner
                .withPropertyValues(propertyName + "=" + invalidValue)
                .run(context -> assertThat(context).hasFailed());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "app.auth.rate-limit.auth-peer-limit",
        "app.auth.rate-limit.login-account-limit",
        "app.auth.rate-limit.signup-email-limit",
        "app.auth.rate-limit.account-email-limit",
        "app.auth.rate-limit.action-token-limit",
        "app.auth.rate-limit.deletion-user-limit",
        "app.auth.rate-limit.maximum-entries"
    })
    void rejectsZeroAndNegativeRateLimits(String propertyName) {
        for (String invalidValue : new String[] {"0", "-1"}) {
            contextRunner
                .withPropertyValues(propertyName + "=" + invalidValue)
                .run(context -> assertThat(context).hasFailed());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"bad cookie", "bad;cookie", "bad=cookie"})
    void rejectsIllegalRefreshCookieNames(String cookieName) {
        contextRunner
            .withPropertyValues("app.refresh-token.cookie-name=" + cookieName)
            .run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Lax", "None", "strict"})
    void rejectsRefreshCookieSameSiteOtherThanStrict(String sameSite) {
        contextRunner
            .withPropertyValues("app.refresh-token.cookie-same-site=" + sameSite)
            .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void secureCookiesDefaultToTrue() {
        configFileContextRunner()
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(AppProperties.class).refreshToken().cookieSecure()).isTrue();
            });
    }

    @Test
    void localConfigurationExplicitlyDisablesSecureCookies() {
        configFileContextRunner()
            .withPropertyValues("spring.profiles.active=local")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(AppProperties.class).refreshToken().cookieSecure()).isFalse();
            });
    }

    @Test
    void acceptsAndNormalizesFrontendOrigin() {
        contextRunner
            .withPropertyValues("app.frontend-base-url=HTTP://LOCALHOST:80/")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(AppProperties.class).frontendBaseUrl())
                    .isEqualTo(URI.create("http://localhost/"));
            });
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/relative",
        "ftp://example.com",
        "http://user@example.com",
        "http://example.com/app",
        "http://example.com?q=1",
        "http://example.com/#fragment"
    })
    void rejectsInvalidFrontendOrigins(String origin) {
        contextRunner
            .withPropertyValues("app.frontend-base-url=" + origin)
            .run(context -> assertThat(context).hasFailed());
    }

    private static ApplicationContextRunner baseContextRunner() {
        return new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                ConfigurationPropertiesAutoConfiguration.class,
                ValidationAutoConfiguration.class
            ))
            .withUserConfiguration(PropertiesConfiguration.class);
    }

    private static ApplicationContextRunner configFileContextRunner() {
        return baseContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withPropertyValues(
                "SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/test",
                "SPRING_DATASOURCE_USERNAME=test",
                "SPRING_DATASOURCE_PASSWORD=test",
                "SPRING_MAIL_HOST=localhost",
                "SPRING_MAIL_PORT=1025",
                "APP_FRONTEND_BASE_URL=http://localhost:3000",
                "APP_EMAIL_FROM=Adept Test <test@adept.local>",
                "APP_JWT_SECRET_BASE64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                "APP_TOKEN_HASH_PEPPER_BASE64=BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBA=",
                "APP_INTEGRATION_ENCRYPTION_ACTIVE_KEY_VERSION=1",
                "APP_INTEGRATION_ENCRYPTION_KEY_V1_BASE64=CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCA=",
                "APP_INTERNAL_ENGINE_TOKEN=test-only-engine-token"
            );
    }

    private static String[] validPropertiesWithout(String propertyName) {
        String prefix = propertyName + "=";
        return Arrays.stream(validProperties())
            .filter(property -> !property.startsWith(prefix))
            .toArray(String[]::new);
    }

    private static String[] validProperties() {
        return new String[] {
            "app.frontend-base-url=http://localhost:3000",
            "app.public-api-base-url=http://localhost:8080",
            "app.email-from=Adept Test <test@adept.local>",
            "app.jwt.secret-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            "app.jwt.issuer=adept-api",
            "app.jwt.audience=adept-frontend",
            "app.jwt.access-token-ttl=PT15M",
            "app.refresh-token.ttl=P7D",
            "app.refresh-token.cookie-name=adept_refresh",
            "app.refresh-token.cookie-secure=false",
            "app.refresh-token.cookie-same-site=Strict",
            "app.token-hash-pepper-base64=BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBA=",
            "app.integration-encryption.active-key-version=1",
            "app.integration-encryption.keys[1]=CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCA=",
            "app.github.enabled=false",
            "app.github.app-id=",
            "app.github.app-slug=",
            "app.github.private-key-base64=",
            "app.github.webhook-secret=",
            "app.jira.enabled=false",
            "app.jira.client-id=",
            "app.jira.client-secret=",
            "app.jira.callback-url=http://localhost:8080/api/v1/integrations/jira/callback",
            "app.engine.base-url=http://localhost:8000",
            "app.engine.internal-token=test-only-engine-token",
            "app.auth.bcrypt-cost=12",
            "app.auth.verification-token-ttl=PT24H",
            "app.auth.reset-token-ttl=PT1H",
            "app.auth.rate-limit.auth-peer-limit=30000",
            "app.auth.rate-limit.auth-peer-window=PT15M",
            "app.auth.rate-limit.login-account-limit=10",
            "app.auth.rate-limit.login-window=PT15M",
            "app.auth.rate-limit.signup-email-limit=3",
            "app.auth.rate-limit.signup-window=PT1H",
            "app.auth.rate-limit.account-email-limit=5",
            "app.auth.rate-limit.account-email-window=PT1H",
            "app.auth.rate-limit.action-token-limit=10",
            "app.auth.rate-limit.action-token-window=PT15M",
            "app.auth.rate-limit.deletion-user-limit=10",
            "app.auth.rate-limit.deletion-user-window=PT15M",
            "app.auth.rate-limit.maximum-entries=100000"
        };
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AppProperties.class)
    static class PropertiesConfiguration {
    }
}
