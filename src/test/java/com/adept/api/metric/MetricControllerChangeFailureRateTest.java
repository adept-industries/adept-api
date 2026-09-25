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

import com.adept.api.common.domain.DeploymentStatus;
import com.adept.api.common.domain.IncidentSeverity;
import com.adept.api.common.domain.MembershipRole;
import com.adept.api.common.domain.MetricType;
import com.adept.api.metric.dto.ChangeFailureRateDetailDto;
import com.adept.api.metric.dto.ChangeFailureRateDetailsResponse;
import com.adept.api.metric.dto.ChangeFailureRateIncidentRefDto;
import com.adept.api.security.AuthenticatedPrincipal;
import com.adept.api.security.CurrentPrincipal;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the Change Failure Rate endpoints on {@link MetricController}.
 */
@ExtendWith(MockitoExtension.class)
class MetricControllerChangeFailureRateTest {

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

    private ChangeFailureRateDetailsResponse sampleResponse() {
        ChangeFailureRateDetailDto failed = new ChangeFailureRateDetailDto(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "engine",
            "acme/engine",
            Instant.parse("2026-09-03T10:00:00Z"),
            "production",
            DeploymentStatus.SUCCESS,
            "inc0001",
            true,
            new ChangeFailureRateIncidentRefDto(UUID.randomUUID(), "Checkout 500s", IncidentSeverity.SEV1)
        );
        ChangeFailureRateDetailDto healthy = new ChangeFailureRateDetailDto(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "engine",
            "acme/engine",
            Instant.parse("2026-09-02T10:00:00Z"),
            "production",
            DeploymentStatus.SUCCESS,
            "good002",
            false,
            null
        );
        return new ChangeFailureRateDetailsResponse(
            workspaceId, null, null, 1,
            Instant.parse("2026-09-01T00:00:00Z"),
            Instant.parse("2026-09-08T00:00:00Z"),
            "UTC", 0, 20, 2L, 1,
            2L, 1L, 50.0,
            List.of(failed, healthy)
        );
    }

    @Test
    void changeFailureRateDetailsEndpointReturnsServiceResponse() {
        when(currentPrincipal.require()).thenReturn(principal);
        ChangeFailureRateDetailsResponse expected = sampleResponse();
        when(metricService.getChangeFailureRateDetails(principal, null, null, null, null, 0, 20))
            .thenReturn(expected);

        ResponseEntity<ChangeFailureRateDetailsResponse> response =
            metricController.getChangeFailureRateDetails(null, null, null, null, 0, 20);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(expected);
        assertThat(response.getBody().failedDeployments()).isEqualTo(1L);
        assertThat(response.getBody().items().get(0).isFailure()).isTrue();
        assertThat(response.getBody().items().get(0).incident().title()).isEqualTo("Checkout 500s");
        assertThat(response.getBody().items().get(1).incident()).isNull();
    }

    @Test
    void sharedDetailsEndpointRoutesChangeFailureRateMetricType() {
        when(currentPrincipal.require()).thenReturn(principal);
        UUID projectId = UUID.randomUUID();
        Instant from = Instant.parse("2026-09-01T00:00:00Z");
        Instant to   = Instant.parse("2026-09-08T00:00:00Z");
        ChangeFailureRateDetailsResponse expected = sampleResponse();
        when(metricService.getChangeFailureRateDetails(principal, projectId, null, from, to, 1, 50))
            .thenReturn(expected);

        ResponseEntity<?> response = metricController.getDetails(
            projectId, null, MetricType.CHANGE_FAILURE_RATE_PERCENT, from, to, 1, 50);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(expected);
    }

    @Test
    void serializesFailureFlagAsIsFailure() {
        JsonNode json = JsonMapper.builder().build().valueToTree(sampleResponse());

        JsonNode first = json.get("items").get(0);
        assertThat(first.get("isFailure").asBoolean()).isTrue();
        assertThat(first.has("failure")).isFalse();
        assertThat(first.get("incident").get("severity").asString()).isEqualTo("SEV1");
        assertThat(json.get("items").get(1).get("incident").isNull()).isTrue();
        assertThat(json.get("failureRatePercent").asDouble()).isEqualTo(50.0);
    }
}
