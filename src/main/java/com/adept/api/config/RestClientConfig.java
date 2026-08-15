package com.adept.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
public class RestClientConfig {

    @Bean
    @Scope("prototype")
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }
}
