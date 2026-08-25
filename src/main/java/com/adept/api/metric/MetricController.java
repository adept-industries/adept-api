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
import com.adept.api.metric.dto.DoraMetricsSeriesResponse;
import com.adept.api.metric.dto.DoraMetricsSummaryResponse;
import com.adept.api.security.AuthenticatedPrincipal;
import com.adept.api.security.CurrentPrincipal;

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
    public ResponseEntity<DoraMetricsSummaryResponse> getSummary(
            @RequestParam(required = false) UUID projectId,
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
    public ResponseEntity<DoraMetricsSeriesResponse> getSeries(
            @RequestParam(required = false) UUID projectId,
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
}
