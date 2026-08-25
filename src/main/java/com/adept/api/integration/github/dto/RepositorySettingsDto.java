package com.adept.api.integration.github.dto;

import java.util.List;

import com.adept.api.common.domain.MetricGranularity;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

public record RepositorySettingsDto(
    @Pattern(regexp = "^(WORKFLOW_RUN|DEPLOYMENT)$", message = "deploymentSignal must be WORKFLOW_RUN or DEPLOYMENT")
    String deploymentSignal,

    List<String> productionBranchPatterns,

    List<String> productionEnvironmentPatterns,

    List<String> deploymentWorkflowNamePatterns,

    @Pattern(regexp = "^(GITHUB|JIRA|MANUAL|BOTH)$", message = "incidentSource must be GITHUB, JIRA, MANUAL, or BOTH")
    String incidentSource,

    List<String> doraExclusions,

    MetricGranularity defaultMetricGranularity,

    @Min(value = 1, message = "backfillDays must be at least 1")
    @Max(value = 365, message = "backfillDays must be at most 365")
    Integer backfillDays
) {
    public static RepositorySettingsDto defaults() {
        return new RepositorySettingsDto(
            "WORKFLOW_RUN",
            List.of("main", "master", "release/*"),
            List.of("production", "prod"),
            List.of("*deploy*", "*production*", "*release*"),
            "GITHUB",
            List.of(),
            MetricGranularity.WEEK,
            90
        );
    }
}
