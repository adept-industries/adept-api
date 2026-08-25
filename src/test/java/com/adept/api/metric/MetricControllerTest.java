package com.adept.api.metric;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.adept.api.common.domain.MembershipRole;
import com.adept.api.common.domain.MetricGranularity;
import com.adept.api.common.domain.MetricType;
import com.adept.api.metric.dto.DoraMetricsSeriesResponse;
import com.adept.api.metric.dto.DoraMetricsSummaryResponse;
import com.adept.api.metric.dto.MetricSeriesItemDto;
import com.adept.api.metric.dto.MetricSummaryDto;
import com.adept.api.security.AuthenticatedPrincipal;
import com.adept.api.security.CurrentPrincipal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MetricControllerTest {

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
            MembershipRole.MANAGER,
            1
        );
    }

    @Test
    void testGetSummaryReturnsOk() {
        when(currentPrincipal.require()).thenReturn(principal);

        Instant now = Instant.now();
        DoraMetricsSummaryResponse mockResponse = new DoraMetricsSummaryResponse(
            workspaceId,
            null,
            null,
            2,
            now.minusSeconds(86400 * 30),
            now,
            "UTC",
            "dora-v2",
            new MetricSummaryDto(BigDecimal.valueOf(4.5), "deployments/week", 18, MetricRating.HIGH, Map.of()),
            new MetricSummaryDto(BigDecimal.valueOf(1.2), "hours", 12, MetricRating.HIGH, Map.of()),
            new MetricSummaryDto(BigDecimal.valueOf(0.8), "hours", 2, MetricRating.ELITE, Map.of()),
            new MetricSummaryDto(BigDecimal.valueOf(5.0), "percent", 18, MetricRating.ELITE, Map.of()),
            now,
            false
        );

        when(metricService.getSummary(principal, null, null, null, null))
            .thenReturn(mockResponse);

        ResponseEntity<DoraMetricsSummaryResponse> response = metricController.getSummary(
            null,
            null,
            null,
            null
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().repositoryCount()).isEqualTo(2);
        assertThat(response.getBody().deploymentFrequency().value()).isEqualByComparingTo("4.5");
    }

    @Test
    void testGetSeriesReturnsOk() {
        when(currentPrincipal.require()).thenReturn(principal);

        Instant t1 = Instant.parse("2026-08-01T00:00:00Z");
        Instant t2 = Instant.parse("2026-08-02T00:00:00Z");

        MetricSeriesItemDto item = new MetricSeriesItemDto(
            MetricType.DEPLOYMENT_FREQUENCY,
            t1,
            t2,
            BigDecimal.valueOf(3.0),
            "deployments/day",
            3,
            Map.of()
        );

        DoraMetricsSeriesResponse mockResponse = new DoraMetricsSeriesResponse(
            workspaceId,
            null,
            null,
            1,
            t1,
            t2,
            "UTC",
            MetricGranularity.DAY,
            "dora-v2",
            t2,
            false,
            List.of(item)
        );

        when(metricService.getSeries(principal, null, null, null, MetricGranularity.DAY, null, null))
            .thenReturn(mockResponse);

        ResponseEntity<DoraMetricsSeriesResponse> response = metricController.getSeries(
            null,
            null,
            null,
            MetricGranularity.DAY,
            null,
            null
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().series()).hasSize(1);
        assertThat(response.getBody().series().get(0).value()).isEqualByComparingTo("3.0");
    }
}
