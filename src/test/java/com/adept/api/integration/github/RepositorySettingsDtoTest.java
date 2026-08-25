package com.adept.api.integration.github;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.adept.api.common.domain.MetricGranularity;
import com.adept.api.integration.github.dto.RepositorySettingsDto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;

class RepositorySettingsDtoTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void acceptsOnlyDeploymentSignalsRepresentedByTheNormalizedDeploymentModel() {
        assertThat(validator.validate(settings("WORKFLOW_RUN"))).isEmpty();
        assertThat(validator.validate(settings("DEPLOYMENT"))).isEmpty();

        assertThat(validator.validate(settings("PUSH")))
            .singleElement()
            .satisfies(violation -> assertThat(violation.getMessage())
                .isEqualTo("deploymentSignal must be WORKFLOW_RUN or DEPLOYMENT"));
        assertThat(validator.validate(settings("RELEASE_TAG"))).hasSize(1);
        assertThat(validator.validate(settings("MERGE_TO_BRANCH"))).hasSize(1);
    }

    @Test
    void rejectsBlankOversizedAndExcessivePatterns() {
        RepositorySettingsDto invalid = new RepositorySettingsDto(
            "DEPLOYMENT",
            java.util.Collections.nCopies(33, "main"),
            List.of(" "),
            List.of("x".repeat(129)),
            "GITHUB",
            List.of(" "),
            MetricGranularity.WEEK,
            90
        );

        assertThat(validator.validate(invalid)).hasSize(4);
    }

    private RepositorySettingsDto settings(String deploymentSignal) {
        return new RepositorySettingsDto(
            deploymentSignal,
            List.of("main"),
            List.of("production"),
            List.of("deploy"),
            "GITHUB",
            List.of(),
            MetricGranularity.WEEK,
            90
        );
    }
}
