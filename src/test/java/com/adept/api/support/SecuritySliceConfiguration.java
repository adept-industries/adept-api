package com.adept.api.support;

import java.time.Clock;

import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import com.adept.api.config.AppProperties;
import com.adept.api.security.JwtService;
import com.adept.api.security.PrincipalValidationService;
import com.adept.api.workspace.MembershipRepository;

@TestConfiguration(proxyBeanMethods = false)
public class SecuritySliceConfiguration {

    @Bean
    AppProperties appProperties() {
        return TestAppProperties.create(TestAppProperties.rateLimit(2, 100));
    }

    @Bean
    MembershipRepository membershipRepository() {
        return Mockito.mock(MembershipRepository.class);
    }

    @Bean
    PrincipalValidationService principalValidationService(MembershipRepository membershipRepository) {
        return new PrincipalValidationService(membershipRepository);
    }

    @Bean
    JwtService jwtService(AppProperties appProperties, Clock clock) {
        return new JwtService(appProperties, clock);
    }
}
