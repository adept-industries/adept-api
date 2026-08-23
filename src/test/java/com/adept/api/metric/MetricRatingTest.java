package com.adept.api.metric;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MetricRatingTest {

    @Test
    void testDeploymentFrequencyRatings() {
        assertThat(MetricRating.rateDeploymentFrequency(10.0)).isEqualTo(MetricRating.ELITE);
        assertThat(MetricRating.rateDeploymentFrequency(7.0)).isEqualTo(MetricRating.ELITE);
        assertThat(MetricRating.rateDeploymentFrequency(3.5)).isEqualTo(MetricRating.HIGH);
        assertThat(MetricRating.rateDeploymentFrequency(1.0)).isEqualTo(MetricRating.HIGH);
        assertThat(MetricRating.rateDeploymentFrequency(0.5)).isEqualTo(MetricRating.MEDIUM);
        assertThat(MetricRating.rateDeploymentFrequency(0.1)).isEqualTo(MetricRating.LOW);
    }

    @Test
    void testChangeLeadTimeRatings() {
        assertThat(MetricRating.rateChangeLeadTime(0.5, 5)).isEqualTo(MetricRating.ELITE);
        assertThat(MetricRating.rateChangeLeadTime(12.0, 5)).isEqualTo(MetricRating.HIGH);
        assertThat(MetricRating.rateChangeLeadTime(48.0, 5)).isEqualTo(MetricRating.MEDIUM);
        assertThat(MetricRating.rateChangeLeadTime(200.0, 5)).isEqualTo(MetricRating.LOW);
        assertThat(MetricRating.rateChangeLeadTime(0.0, 0)).isEqualTo(MetricRating.UNKNOWN);
    }

    @Test
    void testRecoveryTimeRatings() {
        assertThat(MetricRating.rateRecoveryTime(0.5, 3)).isEqualTo(MetricRating.ELITE);
        assertThat(MetricRating.rateRecoveryTime(4.0, 3)).isEqualTo(MetricRating.HIGH);
        assertThat(MetricRating.rateRecoveryTime(72.0, 3)).isEqualTo(MetricRating.MEDIUM);
        assertThat(MetricRating.rateRecoveryTime(200.0, 3)).isEqualTo(MetricRating.LOW);
        assertThat(MetricRating.rateRecoveryTime(0.0, 0)).isEqualTo(MetricRating.UNKNOWN);
    }

    @Test
    void testChangeFailureRateRatings() {
        assertThat(MetricRating.rateChangeFailureRate(2.5, 10)).isEqualTo(MetricRating.ELITE);
        assertThat(MetricRating.rateChangeFailureRate(8.0, 10)).isEqualTo(MetricRating.HIGH);
        assertThat(MetricRating.rateChangeFailureRate(14.0, 10)).isEqualTo(MetricRating.MEDIUM);
        assertThat(MetricRating.rateChangeFailureRate(25.0, 10)).isEqualTo(MetricRating.LOW);
        assertThat(MetricRating.rateChangeFailureRate(0.0, 0)).isEqualTo(MetricRating.UNKNOWN);
    }
}
