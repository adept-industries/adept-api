package com.adept.api.metric.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * One row returned by the Change Lead Time details endpoint.
 *
 * <p>Lead time is measured from {@code firstCommitAt} to {@code deployedAt}.
 * The three breakdown stages are provided when all required timestamps are present;
 * otherwise each stage field may be {@code null}.
 *
 * <ul>
 *   <li><b>Coding time</b>  – {@code openedAt - firstCommitAt}</li>
 *   <li><b>Review time</b>  – {@code mergedAt  - openedAt}</li>
 *   <li><b>Deploy time</b>  – {@code deployedAt - mergedAt}</li>
 * </ul>
 */
public record ChangeLeadTimeDetailDto(
    UUID   prId,
    int    prNumber,
    String prTitle,
    String prUrl,
    String authorLogin,

    UUID   repositoryId,
    String repositoryName,
    String repositoryOwnerLogin,
    String repositoryFullName,

    Instant firstCommitAt,
    Instant openedAt,
    Instant mergedAt,
    Instant deployedAt,

    /** Total lead time in seconds (deployedAt − firstCommitAt). Null when firstCommitAt is missing. */
    Long    leadTimeSeconds,
    /** Coding stage in seconds (openedAt − firstCommitAt). Null when either timestamp is missing. */
    Long    codingTimeSeconds,
    /** Review / merge stage in seconds (mergedAt − openedAt). Null when either timestamp is missing. */
    Long    reviewTimeSeconds,
    /** Deploy stage in seconds (deployedAt − mergedAt). Null when mergedAt is missing. */
    Long    deployTimeSeconds,

    String  deploymentEnvironment,
    String  deploymentCommitSha
) {}
