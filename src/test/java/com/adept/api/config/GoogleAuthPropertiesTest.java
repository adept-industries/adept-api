package com.adept.api.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class GoogleAuthPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(
            ConfigurationPropertiesAutoConfiguration.class,
            ValidationAutoConfiguration.class
        ))
        .withUserConfiguration(PropertiesConfiguration.class)
        .withPropertyValues(
            "app.google-auth.enabled=false",
            "app.google-auth.client-id=",
            "app.google-auth.client-secret=",
            "app.google-auth.redirect-uri=http://localhost:8080/api/v1/auth/google/callback/google",
            "app.google-auth.onboarding-ttl=PT10M"
        );

    @Test
    void disabledConfigurationDoesNotRequireCredentials() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(GoogleAuthProperties.class).enabled()).isFalse();
        });
    }

    @Test
    void enabledConfigurationRequiresBothCredentials() {
        contextRunner
            .withPropertyValues("app.google-auth.enabled=true")
            .run(context -> assertThat(context).hasFailed());

        contextRunner
            .withPropertyValues(
                "app.google-auth.enabled=true",
                "app.google-auth.client-id=test-client",
                "app.google-auth.client-secret=test-secret"
            )
            .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void productionRedirectMustUseHttpsAndTtlMustBePositive() {
        contextRunner
            .withPropertyValues("app.google-auth.redirect-uri=http://example.com/callback")
            .run(context -> assertThat(context).hasFailed());

        contextRunner
            .withPropertyValues("app.google-auth.onboarding-ttl=PT0S")
            .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(GoogleAuthProperties.class)
    static class PropertiesConfiguration {
    }
}

