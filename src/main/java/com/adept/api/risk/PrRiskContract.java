package com.adept.api.risk;

import java.util.List;

/** Frozen identity shared by persisted Phase 7 features and predictions. */
public final class PrRiskContract {

    public static final String MODEL_NAME = "jitfine-expert-pr-risk-mvp";
    public static final String MODEL_VERSION = "jitfine-expert-pr-risk-mvp-v1";
    public static final String FEATURE_SCHEMA_VERSION = "jitfine-pr-features-v1";
    public static final List<String> FEATURE_ORDER = List.of(
        "ns",
        "nd",
        "nf",
        "entropy",
        "la",
        "ld",
        "fix"
    );
    public static final String DISPLAY_LABEL = "Estimated review risk";
    public static final String DISCLAIMER =
        "This score helps prioritize code review. "
            + "It does not prove that the pull request contains a defect.";

    private PrRiskContract() {
    }

    public static boolean isCurrent(RiskPrediction prediction) {
        return prediction != null
            && MODEL_NAME.equals(prediction.getModelName())
            && MODEL_VERSION.equals(prediction.getModelVersion())
            && prediction.getFeature() != null
            && FEATURE_SCHEMA_VERSION.equals(prediction.getFeature().getFeatureSchemaVersion());
    }
}
