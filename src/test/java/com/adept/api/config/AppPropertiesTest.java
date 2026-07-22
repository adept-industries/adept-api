package com.adept.api.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class AppPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(
            ConfigurationPropertiesAutoConfiguration.class,
            ValidationAutoConfiguration.class
        ))
        .withUserConfiguration(PropertiesConfiguration.class)
        .withPropertyValues(validProperties());

    @Test
    void bindsValidConfiguration() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            AppProperties properties = context.getBean(AppProperties.class);
            assertThat(properties.jwt().issuer()).isEqualTo("adept-api");
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
    void rejectsEnabledGithubWithoutCredentials() {
        contextRunner
            .withPropertyValues(
                "app.github.enabled=true",
                "app.github.app-id=",
                "app.github.app-slug="
            )
            .run(context -> assertThat(context).hasFailed());
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
            "app.engine.internal-token=test-only-engine-token"
        };
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AppProperties.class)
    static class PropertiesConfiguration {
    }
}