package com.adept.api.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import com.adept.api.config.AppProperties;

@TestConfiguration(proxyBeanMethods = false)
public class SecuritySliceConfiguration {

    @Bean
    AppProperties appProperties() {
        return TestAppProperties.create(TestAppProperties.rateLimit(2, 100));
    }
}
