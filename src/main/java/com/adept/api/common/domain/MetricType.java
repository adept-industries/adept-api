package com.adept.api.common.domain;

public enum MetricType {
    CHANGE_LEAD_TIME_HOURS, DEPLOYMENT_FREQUENCY,
    FAILED_DEPLOYMENT_RECOVERY_TIME_HOURS, CHANGE_FAILURE_RATE_PERCENT,
    PR_CODING_TIME_HOURS, PR_PICKUP_TIME_HOURS, PR_REVIEW_TIME_HOURS, PR_DEPLOY_TIME_HOURS;

    public boolean isDora() {
        return switch (this) {
            case CHANGE_LEAD_TIME_HOURS, DEPLOYMENT_FREQUENCY,
                FAILED_DEPLOYMENT_RECOVERY_TIME_HOURS, CHANGE_FAILURE_RATE_PERCENT -> true;
            default -> false;
        };
    }
}
