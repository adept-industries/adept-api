package com.adept.api.deployment;

import java.time.Instant;
import java.util.UUID;

/**
 * Flat projection returned by the Change Failure Rate repository query.
 *
 * <p>Each row is one finished production deployment. The incident columns are
 * populated when an incident names this deployment as its {@code failed_deployment_id}.
 */
public interface ChangeFailureRateRow {
    UUID    getDeploymentId();
    UUID    getRepositoryId();
    String  getRepositoryName();
    String  getRepositoryFullName();
    String  getEnvironment();
    String  getStatus();
    String  getCommitSha();
    Instant getFinishedAt();

    UUID    getIncidentId();
    String  getIncidentTitle();
    String  getIncidentSeverity();
}
