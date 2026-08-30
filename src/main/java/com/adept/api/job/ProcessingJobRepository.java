package com.adept.api.job;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProcessingJobRepository
    extends JpaRepository<ProcessingJob, UUID> {

    @Query(
        value = """
            SELECT *
            FROM processing_jobs
            WHERE status IN ('PENDING', 'FAILED')
              AND available_at <= now()
            ORDER BY priority ASC, created_at ASC
            FOR UPDATE SKIP LOCKED
            LIMIT :limit
            """,
        nativeQuery = true
    )
    List<ProcessingJob> lockClaimableJobs(
        @Param("limit") int limit
    );

    @Query(
        value = """
            SELECT *
            FROM processing_jobs
            WHERE job_type = 'RENEW_JIRA_WEBHOOK'
              AND status IN ('PENDING', 'FAILED', 'RUNNING')
              AND payload ->> 'jiraIntegrationId' = :integrationId
            ORDER BY CASE WHEN status = 'RUNNING' THEN 0 ELSE 1 END,
                     created_at ASC,
                     id ASC
            LIMIT 1
            FOR UPDATE
            """,
        nativeQuery = true
    )
    Optional<ProcessingJob> findScheduledJiraWebhookRenewalForUpdate(
        @Param("integrationId") String integrationId
    );

    @Query(
        value = """
            SELECT *
            FROM processing_jobs
            WHERE job_type = 'SYNC_JIRA_PROJECTS'
              AND status IN ('PENDING', 'FAILED', 'RUNNING')
              AND payload ->> 'jiraIntegrationId' = :integrationId
            ORDER BY CASE WHEN status = 'RUNNING' THEN 0 ELSE 1 END,
                     created_at ASC,
                     id ASC
            LIMIT 1
            FOR UPDATE
            """,
        nativeQuery = true
    )
    Optional<ProcessingJob> findActiveJiraProjectSyncForUpdate(
        @Param("integrationId") String integrationId
    );

    boolean existsByRepository_IdAndJobTypeAndStatusIn(
        UUID repositoryId,
        com.adept.api.common.domain.ProcessingJobType jobType,
        List<com.adept.api.common.domain.ProcessingJobStatus> statuses
    );

    @Query(
        value = """
            SELECT EXISTS (
                SELECT 1
                FROM processing_jobs
                WHERE repository_id = :repositoryId
                  AND job_type = 'BACKFILL_REPOSITORY'
                  AND status IN ('PENDING', 'FAILED', 'RUNNING')
                  AND (
                      payload ->> 'issuesOnly' = 'true'
                      OR (
                          COALESCE(payload ->> 'riskOnly', 'false') <> 'true'
                          AND COALESCE(payload ->> 'issuesOnly', 'false') <> 'true'
                      )
                  )
            )
            """,
        nativeQuery = true
    )
    boolean existsActiveIssueBackfill(@Param("repositoryId") UUID repositoryId);
}
