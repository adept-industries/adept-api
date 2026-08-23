package com.adept.api.metric;

public enum MetricRating {
    ELITE,
    HIGH,
    MEDIUM,
    LOW,
    UNKNOWN;

    public static MetricRating rateDeploymentFrequency(double deploysPerWeek) {
        if (deploysPerWeek >= 7.0) {
            return ELITE;
        }
        if (deploysPerWeek >= 1.0) {
            return HIGH;
        }
        if (deploysPerWeek >= 0.25) {
            return MEDIUM;
        }
        return LOW;
    }

    public static MetricRating rateChangeLeadTime(double hours, int sampleSize) {
        if (sampleSize == 0 || hours <= 0) {
            return UNKNOWN;
        }
        if (hours < 1.0) {
            return ELITE;
        }
        if (hours <= 24.0) {
            return HIGH;
        }
        if (hours <= 168.0) {
            return MEDIUM;
        }
        return LOW;
    }

    public static MetricRating rateRecoveryTime(double hours, int sampleSize) {
        if (sampleSize == 0 || hours <= 0) {
            return UNKNOWN;
        }
        if (hours < 1.0) {
            return ELITE;
        }
        if (hours <= 24.0) {
            return HIGH;
        }
        if (hours <= 168.0) {
            return MEDIUM;
        }
        return LOW;
    }

    public static MetricRating rateChangeFailureRate(double percent, int sampleSize) {
        if (sampleSize == 0) {
            return UNKNOWN;
        }
        if (percent <= 5.0) {
            return ELITE;
        }
        if (percent <= 10.0) {
            return HIGH;
        }
        if (percent <= 15.0) {
            return MEDIUM;
        }
        return LOW;
    }
}
