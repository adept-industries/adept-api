package com.adept.api.deployment;

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
public interface DeploymentRepository extends JpaRepository<Deployment, UUID> {

    @Query(value = """
        SELECT d FROM Deployment d
        JOIN FETCH d.repository r
        WHERE d.repository.id IN :repositoryIds
          AND d.production = true
          AND d.status = com.adept.api.common.domain.DeploymentStatus.SUCCESS
          AND d.finishedAt >= :from
          AND d.finishedAt < :to
        ORDER BY d.finishedAt DESC
        """,
        countQuery = """
        SELECT count(d) FROM Deployment d
        WHERE d.repository.id IN :repositoryIds
          AND d.production = true
          AND d.status = com.adept.api.common.domain.DeploymentStatus.SUCCESS
          AND d.finishedAt >= :from
          AND d.finishedAt < :to
        """)
    Page<Deployment> findSuccessfulProductionDeployments(
        @Param("repositoryIds") Collection<UUID> repositoryIds,
        @Param("from") Instant from,
        @Param("to") Instant to,
        Pageable pageable
    );

    /**
     * Returns every finished production deployment that forms the Change Failure Rate
     * denominator, newest first.
     *
     * <p>Matches the engine calculation: production deployments whose {@code finished_at}
     * falls in {@code [from, to)}, regardless of status. The incident whose
     * {@code failed_deployment_id} points at the deployment is joined in; that column is
     * unique, so each deployment appears exactly once and the total stays equal to the
     * engine's {@code total_deployments}.
     *
     * @param repositoryIds  IDs of repositories accessible to the caller
     * @param from           inclusive start of the finish window
     * @param to             exclusive end of the finish window
     * @param pageable       page only; rows are ordered by finish time, newest first
     */
    @Query(value = """
        SELECT
            d.id                 AS deployment_id,
            r.id                 AS repository_id,
            r.name               AS repository_name,
            r.full_name          AS repository_full_name,
            d.environment        AS environment,
            d.status             AS status,
            d.commit_sha         AS commit_sha,
            d.finished_at        AS finished_at,
            i.id                 AS incident_id,
            i.title              AS incident_title,
            i.severity           AS incident_severity
        FROM deployments d
        JOIN repositories r   ON r.id = d.repository_id
        LEFT JOIN incidents i ON i.failed_deployment_id = d.id
        WHERE d.repository_id IN (:repositoryIds)
          AND d.is_production = true
          AND d.finished_at IS NOT NULL
          AND d.finished_at >= :from
          AND d.finished_at <  :to
        ORDER BY d.finished_at DESC, d.id
        """,
        countQuery = """
        SELECT count(*)
        FROM deployments d
        WHERE d.repository_id IN (:repositoryIds)
          AND d.is_production = true
          AND d.finished_at IS NOT NULL
          AND d.finished_at >= :from
          AND d.finished_at <  :to
        """,
        nativeQuery = true)
    Page<ChangeFailureRateRow> findChangeFailureRateRows(
        @Param("repositoryIds") Collection<UUID> repositoryIds,
        @Param("from") Instant from,
        @Param("to") Instant to,
        Pageable pageable
    );

    /**
     * Counts the Change Failure Rate numerator: finished production deployments in
     * {@code [from, to)} that either ended in {@code FAILURE} or are referenced by an
     * incident's {@code failed_deployment_id}.
     */
    @Query(value = """
        SELECT count(*)
        FROM deployments d
        WHERE d.repository_id IN (:repositoryIds)
          AND d.is_production = true
          AND d.finished_at IS NOT NULL
          AND d.finished_at >= :from
          AND d.finished_at <  :to
          AND (
              d.status = 'FAILURE'
              OR EXISTS (SELECT 1 FROM incidents i WHERE i.failed_deployment_id = d.id)
          )
        """,
        nativeQuery = true)
    long countFailedProductionDeployments(
        @Param("repositoryIds") Collection<UUID> repositoryIds,
        @Param("from") Instant from,
        @Param("to") Instant to
    );
}
