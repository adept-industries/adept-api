package com.adept.api.incident;

import java.time.Instant;
import java.util.Collection;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface IncidentRepository extends JpaRepository<Incident, UUID> {

    /**
     * Returns the resolved incidents that contribute to Failed Deployment Recovery Time.
     *
     * <p>Matches the engine calculation: only {@code RESOLVED} incidents with a
     * {@code resolved_at} are considered, the resolution instant is
     * {@code COALESCE(recovery.finished_at, i.resolved_at)}, that instant must fall in
     * {@code [from, to)}, and it must not precede {@code detected_at}. Open incidents are
     * never returned, even when they were detected inside the window.
     *
     * @param repositoryIds  IDs of repositories accessible to the caller
     * @param from           inclusive start of the resolution window
     * @param to             exclusive end of the resolution window
     * @param pageable       page only; rows are ordered by resolution time, newest first
     */
    @Query(value = """
        SELECT
            i.id                                        AS incident_id,
            i.title                                     AS title,
            i.source                                    AS source,
            i.severity                                  AS severity,
            r.id                                        AS repository_id,
            r.name                                      AS repository_name,
            r.full_name                                 AS repository_full_name,
            i.detected_at                               AS detected_at,
            i.resolved_at                               AS resolved_at,
            COALESCE(recovery_d.finished_at, i.resolved_at) AS effective_resolved_at,
            failed_d.id                                 AS failed_deployment_id,
            failed_d.commit_sha                         AS failed_deployment_commit_sha,
            failed_d.environment                        AS failed_deployment_environment,
            failed_d.finished_at                        AS failed_deployment_finished_at,
            recovery_d.id                               AS recovery_deployment_id,
            recovery_d.commit_sha                       AS recovery_deployment_commit_sha,
            recovery_d.environment                      AS recovery_deployment_environment,
            recovery_d.finished_at                      AS recovery_deployment_finished_at
        FROM incidents i
        JOIN repositories r              ON r.id = i.repository_id
        LEFT JOIN deployments failed_d   ON failed_d.id = i.failed_deployment_id
        LEFT JOIN deployments recovery_d ON recovery_d.id = i.recovery_deployment_id
        WHERE i.repository_id IN (:repositoryIds)
          AND i.status = 'RESOLVED'
          AND i.resolved_at IS NOT NULL
          AND COALESCE(recovery_d.finished_at, i.resolved_at) >= :from
          AND COALESCE(recovery_d.finished_at, i.resolved_at) <  :to
          AND COALESCE(recovery_d.finished_at, i.resolved_at) >= i.detected_at
        ORDER BY effective_resolved_at DESC, i.id
        """,
        countQuery = """
        SELECT count(*)
        FROM incidents i
        LEFT JOIN deployments recovery_d ON recovery_d.id = i.recovery_deployment_id
        WHERE i.repository_id IN (:repositoryIds)
          AND i.status = 'RESOLVED'
          AND i.resolved_at IS NOT NULL
          AND COALESCE(recovery_d.finished_at, i.resolved_at) >= :from
          AND COALESCE(recovery_d.finished_at, i.resolved_at) <  :to
          AND COALESCE(recovery_d.finished_at, i.resolved_at) >= i.detected_at
        """,
        nativeQuery = true)
    Page<RecoveryTimeRow> findRecoveryTimeRows(
        @Param("repositoryIds") Collection<UUID> repositoryIds,
        @Param("from") Instant from,
        @Param("to") Instant to,
        Pageable pageable
    );
}
