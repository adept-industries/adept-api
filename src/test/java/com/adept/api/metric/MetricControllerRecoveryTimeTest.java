package com.adept.api.metric;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.adept.api.common.domain.IncidentSeverity;
import com.adept.api.common.domain.IncidentSource;
import com.adept.api.common.domain.MembershipRole;
import com.adept.api.common.domain.MetricType;
import com.adept.api.metric.dto.RecoveryDeploymentRefDto;
import com.adept.api.metric.dto.RecoveryTimeDetailDto;
import com.adept.api.metric.dto.RecoveryTimeDetailsResponse;
import com.adept.api.security.AuthenticatedPrincipal;
import com.adept.api.security.CurrentPrincipal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the Recovery Time endpoints on {@link MetricController}.
 */
@ExtendWith(MockitoExtension.class)
class MetricControllerRecoveryTimeTest {

    @Mock
    private MetricService metricService;

    @Mock
    private CurrentPrincipal currentPrincipal;

    @InjectMocks
    private MetricController metricController;

    private AuthenticatedPrincipal principal;
    private UUID workspaceId;

    @BeforeEach
    void setUp() {
        workspaceId = UUID.randomUUID();
        principal = new AuthenticatedPrincipal(
            UUID.randomUUID(),
            UUID.randomUUID(),
            workspaceId,
            MembershipRole.LEAD,
            1
        );
    }

    private RecoveryTimeDetailsResponse sampleResponse() {
        Instant detected  = Instant.parse("2026-09-02T10:00:00Z");
        Instant recovered = Instant.parse("2026-09-02T12:30:00Z");
        RecoveryTimeDetailDto row = new RecoveryTimeDetailDto(
            UUID.randomUUID(),
            "Checkout 500s",
            IncidentSource.GITHUB,
            IncidentSeverity.SEV1,
            UUID.randomUUID(),
            "engine",
            "acme/engine",
            detected,
            recovered,
            9000L,
            new RecoveryDeploymentRefDto(UUID.randomUUID(), "bad0001", "production", detected),
            new RecoveryDeploymentRefDto(UUID.randomUUID(), "good002", "production", recovered)
        );
        return new RecoveryTimeDetailsResponse(
            workspaceId, null, null, 1,
            Instant.parse("2026-09-01T00:00:00Z"),
            Instant.parse("2026-09-08T00:00:00Z"),
            "UTC", 0, 20, 1L, 1,
            List.of(row)
        );
    }

    @Test
    void recoveryTimeDetailsEndpointReturnsServiceResponse() {
        when(currentPrincipal.require()).thenReturn(principal);
        RecoveryTimeDetailsResponse expected = sampleResponse();
        when(metricService.getRecoveryTimeDetails(principal, null, null, null, null, 0, 20))
            .thenReturn(expected);

        ResponseEntity<RecoveryTimeDetailsResponse> response =
            metricController.getRecoveryTimeDetails(null, null, null, null, 0, 20);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(expected);
        RecoveryTimeDetailDto item = response.getBody().items().get(0);
        assertThat(item.recoveryDurationSeconds()).isEqualTo(9000L);
        assertThat(item.failedDeployment().commitSha()).isEqualTo("bad0001");
        assertThat(item.recoveryDeployment().commitSha()).isEqualTo("good002");
    }

    @Test
    void sharedDetailsEndpointRoutesRecoveryTimeMetricType() {
        when(currentPrincipal.require()).thenReturn(principal);
        UUID projectId = UUID.randomUUID();
        Instant from = Instant.parse("2026-09-01T00:00:00Z");
        Instant to   = Instant.parse("2026-09-08T00:00:00Z");
        RecoveryTimeDetailsResponse expected = sampleResponse();
        when(metricService.getRecoveryTimeDetails(principal, projectId, null, from, to, 1, 50))
            .thenReturn(expected);

        ResponseEntity<?> response = metricController.getDetails(
            projectId, null, MetricType.FAILED_DEPLOYMENT_RECOVERY_TIME_HOURS, from, to, 1, 50);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(expected);
    }
}
