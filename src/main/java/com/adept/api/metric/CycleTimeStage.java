package com.adept.api.metric;

import com.adept.api.common.domain.MetricType;

// Contiguous pull request stages, in timeline order.
public enum CycleTimeStage {
    CODING(MetricType.PR_CODING_TIME_HOURS),
    PICKUP(MetricType.PR_PICKUP_TIME_HOURS),
    REVIEW(MetricType.PR_REVIEW_TIME_HOURS),
    DEPLOY(MetricType.PR_DEPLOY_TIME_HOURS);

    private final MetricType metricType;

    CycleTimeStage(MetricType metricType) {
        this.metricType = metricType;
    }

    public MetricType metricType() {
        return metricType;
    }
}
