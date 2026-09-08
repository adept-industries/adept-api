package com.adept.api.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.adept.api.auth.PartCIntegrationTestSupport;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("production")
@AutoConfigureMetrics
class PrometheusMetricsIntegrationTest extends PartCIntegrationTestSupport {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void internalScrapeReturnsRealJvmAndDatabaseMetricsWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/actuator/health/readiness"))
            .andExpect(status().isOk());

        mockMvc.perform(get("/actuator/prometheus")
                .with(request -> {
                    request.setRemoteAddr("172.17.0.2");
                    return request;
                }))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith("text/plain"))
            .andExpect(content().string(containsString("jvm_memory_used_bytes")))
            .andExpect(content().string(containsString("hikaricp_connections")))
            .andExpect(content().string(containsString("http_server_requests_seconds_bucket")))
            .andExpect(content().string(containsString("application=\"adept-api\"")))
            .andExpect(content().string(containsString("environment=\"production\"")));
    }

    @Test
    void productionProfileStillDeniesPublicScrapesAndOtherActuatorEndpoints() throws Exception {
        mockMvc.perform(get("/actuator/prometheus")
                .with(request -> {
                    request.setRemoteAddr("203.0.113.5");
                    return request;
                }))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/actuator/env"))
            .andExpect(status().isUnauthorized());
    }
}
