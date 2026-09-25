package com.adept.api.incident;

import java.time.Instant;
import java.util.UUID;

/**
 * Flat projection returned by the Recovery Time repository query.
 *
 * <p>Each row is one resolved incident. {@code effectiveResolvedAt} mirrors the
 * engine: the recovery deployment's finish time when one is linked, otherwise the
 * incident's own {@code resolved_at}.
 */
public interface RecoveryTimeRow {
    UUID    getIncidentId();
    String  getTitle();
    String  getSource();
    String  getSeverity();
    UUID    getRepositoryId();
    String  getRepositoryName();
    String  getRepositoryFullName();
    Instant getDetectedAt();
    Instant getResolvedAt();
    Instant getEffectiveResolvedAt();

    UUID    getFailedDeploymentId();
    String  getFailedDeploymentCommitSha();
    String  getFailedDeploymentEnvironment();
    Instant getFailedDeploymentFinishedAt();

    UUID    getRecoveryDeploymentId();
    String  getRecoveryDeploymentCommitSha();
    String  getRecoveryDeploymentEnvironment();
    Instant getRecoveryDeploymentFinishedAt();
}
