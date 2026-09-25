package com.adept.api.pullrequest;

import java.time.Instant;
import java.util.UUID;

/**
 * Flat projection returned by the Change Lead Time repository query.
 *
 * <p>Each row represents the <em>earliest</em> successful production deployment
 * that includes a given pull request. Because the query uses
 * {@code DISTINCT ON (pr.id) … ORDER BY pr.id, d.finished_at ASC} (expressed
 * as a native query), only one row per PR is returned.
 */
public interface ChangeLeadTimeRow {
    UUID    getPrId();
    int     getPrNumber();
    String  getPrTitle();
    String  getAuthorLogin();
    UUID    getRepositoryId();
    String  getRepositoryName();
    String  getRepositoryOwnerLogin();
    String  getRepositoryFullName();
    Instant getFirstCommitAt();
    Instant getOpenedAt();
    Instant getMergedAt();
    Instant getDeployedAt();
    String  getDeploymentEnvironment();
    String  getDeploymentCommitSha();
}
