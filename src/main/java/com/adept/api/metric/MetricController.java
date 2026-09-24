package com.adept.api.metric;

import java.time.Instant;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.adept.api.common.domain.MetricGranularity;
import com.adept.api.common.domain.MetricType;
import com.adept.api.common.error.ApiException;
import com.adept.api.common.error.ProblemCode;
import com.adept.api.metric.dto.DeploymentFrequencyDetailsResponse;
import com.adept.api.metric.dto.DoraMetricsSeriesResponse;
import com.adept.api.metric.dto.DoraMetricsSummaryResponse;
import com.adept.api.security.AuthenticatedPrincipal;
import com.adept.api.security.CurrentPrincipal;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@Validated
@RestController
@RequestMapping("/api/v1/metrics")
public class MetricController {

    private final MetricService metricService;
    private final CurrentPrincipal currentPrincipal;

    public MetricController(MetricService metricService, CurrentPrincipal currentPrincipal) {
        this.metricService = metricService;
        this.currentPrincipal = currentPrincipal;
    }

    @GetMapping(value = "/summary", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
        summary = "Get a scoped DORA metrics summary",
        description = "When projectId is supplied without repositoryId, metrics are aggregated "
            + "across the repositories readable in that project. When both are supplied, the "
            + "repository must be tracked, attached to that project, and readable by the caller."
    )
    public ResponseEntity<DoraMetricsSummaryResponse> getSummary(
            @Parameter(description = "Optional selected project scope.")
            @RequestParam(required = false) UUID projectId,
            @Parameter(description = "Optional single repository within the selected scope.")
            @RequestParam(required = false) UUID repositoryId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {
        AuthenticatedPrincipal principal = currentPrincipal.require();
        DoraMetricsSummaryResponse response = metricService.getSummary(
            principal,
            projectId,
            repositoryId,
            from,
            to
        );
        return ResponseEntity.ok(response);
    }

    @GetMapping(value = "/series", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
        summary = "Get a scoped DORA metrics time series",
        description = "When projectId is supplied without repositoryId, metrics are aggregated "
            + "across the repositories readable in that project. When both are supplied, the "
            + "repository must be tracked, attached to that project, and readable by the caller."
    )
    public ResponseEntity<DoraMetricsSeriesResponse> getSeries(
            @Parameter(description = "Optional selected project scope.")
            @RequestParam(required = false) UUID projectId,
            @Parameter(description = "Optional single repository within the selected scope.")
            @RequestParam(required = false) UUID repositoryId,
            @RequestParam(required = false) MetricType metricType,
            @RequestParam(required = false, defaultValue = "DAY") MetricGranularity granularity,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {
        AuthenticatedPrincipal principal = currentPrincipal.require();
        DoraMetricsSeriesResponse response = metricService.getSeries(
            principal,
            projectId,
            repositoryId,
            metricType,
            granularity,
            from,
            to
        );
        return ResponseEntity.ok(response);
    }

    @GetMapping(value = "/deployment-frequency/details", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
        summary = "Get Deployment Frequency event details",
        description = "Returns paginated successful production deployments counted by Deployment Frequency."
    )
    public ResponseEntity<DeploymentFrequencyDetailsResponse> getDeploymentFrequencyDetails(
            @Parameter(description = "Optional selected project scope.")
            @RequestParam(required = false) UUID projectId,
            @Parameter(description = "Optional single repository within the selected scope.")
            @RequestParam(required = false) UUID repositoryId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        AuthenticatedPrincipal principal = currentPrincipal.require();
        DeploymentFrequencyDetailsResponse response = metricService.getDeploymentFrequencyDetails(
            principal,
            projectId,
            repositoryId,
            from,
            to,
            page,
            size
        );
        return ResponseEntity.ok(response);
    }

    @GetMapping(value = "/details", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
        summary = "Get scoped DORA metric event details",
        description = "Returns paginated raw event records for a specific DORA metric."
    )
    public ResponseEntity<DeploymentFrequencyDetailsResponse> getDetails(
            @Parameter(description = "Optional selected project scope.")
            @RequestParam(required = false) UUID projectId,
            @Parameter(description = "Optional single repository within the selected scope.")
            @RequestParam(required = false) UUID repositoryId,
            @Parameter(description = "Metric type to fetch details for.")
            @RequestParam(required = false, defaultValue = "DEPLOYMENT_FREQUENCY") MetricType metricType,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        if (metricType != null && metricType != MetricType.DEPLOYMENT_FREQUENCY) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Details for " + metricType + " are not yet supported.");
        }
        return getDeploymentFrequencyDetails(projectId, repositoryId, from, to, page, size);
    }
}
