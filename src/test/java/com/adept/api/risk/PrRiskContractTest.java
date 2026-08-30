package com.adept.api.risk;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.adept.api.pullrequest.PullRequestFeature;

class PrRiskContractTest {

    @Test
    void freezesTheProductionIdentityAndSevenFeatureOrder() {
        assertThat(PrRiskContract.MODEL_NAME).isEqualTo("jitfine-expert-pr-risk-mvp");
        assertThat(PrRiskContract.MODEL_VERSION).isEqualTo("jitfine-expert-pr-risk-mvp-v1");
        assertThat(PrRiskContract.FEATURE_SCHEMA_VERSION).isEqualTo("jitfine-pr-features-v1");
        assertThat(PrRiskContract.FEATURE_ORDER)
            .containsExactly("ns", "nd", "nf", "entropy", "la", "ld", "fix");
    }

    @Test
    void acceptsOnlyTheCurrentModelLinkedToTheCurrentFeatureSchema() {
        PullRequestFeature feature = new PullRequestFeature();
        feature.setFeatureSchemaVersion(PrRiskContract.FEATURE_SCHEMA_VERSION);

        RiskPrediction prediction = new RiskPrediction();
        prediction.setModelName(PrRiskContract.MODEL_NAME);
        prediction.setModelVersion(PrRiskContract.MODEL_VERSION);
        prediction.setFeature(feature);

        assertThat(PrRiskContract.isCurrent(prediction)).isTrue();

        prediction.setModelVersion("legacy-random-forest-v0");
        assertThat(PrRiskContract.isCurrent(prediction)).isFalse();

        prediction.setModelVersion(PrRiskContract.MODEL_VERSION);
        feature.setFeatureSchemaVersion("legacy-fourteen-features-v0");
        assertThat(PrRiskContract.isCurrent(prediction)).isFalse();

        prediction.setFeature(null);
        assertThat(PrRiskContract.isCurrent(prediction)).isFalse();
    }

    @Test
    void freezesRequiredDecisionSupportWording() {
        assertThat(PrRiskContract.DISPLAY_LABEL).isEqualTo("Estimated review risk");
        assertThat(PrRiskContract.DISCLAIMER)
            .isEqualTo(
                "This score helps prioritize code review. "
                    + "It does not prove that the pull request contains a defect."
            );
    }
}
