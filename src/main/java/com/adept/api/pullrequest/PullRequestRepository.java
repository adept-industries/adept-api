package com.adept.api.pullrequest;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PullRequestRepository extends JpaRepository<PullRequest, UUID> {

    Optional<PullRequest> findByRepositoryIdAndGithubPrId(UUID repositoryId, long githubPrId);

    Optional<PullRequest> findByRepositoryIdAndNumber(UUID repositoryId, int number);

    List<PullRequest> findAllByRepositoryIdOrderByOpenedAtDesc(UUID repositoryId);

    @Query("""
        select pr
        from PullRequest pr
        where pr.repository.id = :repositoryId
          and pr.authorLogin = :authorLogin
          and pr.openedAt < :openedAt
        order by pr.openedAt asc, pr.number asc
        """)
    List<PullRequest> findPriorByRepositoryAndAuthor(
        @Param("repositoryId") UUID repositoryId,
        @Param("authorLogin") String authorLogin,
        @Param("openedAt") Instant openedAt
    );

    /**
     * Returns one row per pull request (earliest successful production deployment)
     * for the Change Lead Time metric.
     *
     * <p>Uses a native {@code DISTINCT ON} query so only the chronologically first
     * deployment per PR is returned, matching the DORA definition of lead time.
     * Only PRs whose {@code first_commit_at} is populated are included.
     *
     * @param repositoryIds  IDs of repositories accessible to the caller
     * @param from           inclusive start of the deployment {@code finished_at} window
     * @param to             exclusive end of the deployment {@code finished_at} window
     * @param pageable       page and sort (sort is applied in the outer query)
     */
    @Query(value = """
        SELECT *
        FROM (
            SELECT DISTINCT ON (pr.id)
                pr.id                        AS pr_id,
                pr.number                    AS pr_number,
                pr.title                     AS pr_title,
                pr.author_login              AS author_login,
                r.id                         AS repository_id,
                r.name                       AS repository_name,
                r.owner_login                AS repository_owner_login,
                r.full_name                  AS repository_full_name,
                pr.first_commit_at           AS first_commit_at,
                pr.opened_at                 AS opened_at,
                pr.merged_at                 AS merged_at,
                d.finished_at                AS deployed_at,
                d.environment                AS deployment_environment,
                d.commit_sha                 AS deployment_commit_sha
            FROM pull_requests pr
            JOIN deployment_pull_requests dpr ON dpr.pull_request_id = pr.id
            JOIN deployments d               ON d.id = dpr.deployment_id
            JOIN repositories r              ON r.id = pr.repository_id
            WHERE r.id IN (:repositoryIds)
              AND d.is_production = true
              AND d.status = 'SUCCESS'
              AND d.finished_at >= :from
              AND d.finished_at <  :to
              AND pr.first_commit_at IS NOT NULL
            ORDER BY pr.id, d.finished_at ASC
        ) sub
        ORDER BY sub.deployed_at DESC
        """,
        countQuery = """
        SELECT count(DISTINCT pr.id)
        FROM pull_requests pr
        JOIN deployment_pull_requests dpr ON dpr.pull_request_id = pr.id
        JOIN deployments d               ON d.id = dpr.deployment_id
        JOIN repositories r              ON r.id = pr.repository_id
        WHERE r.id IN (:repositoryIds)
          AND d.is_production = true
          AND d.status = 'SUCCESS'
          AND d.finished_at >= :from
          AND d.finished_at <  :to
          AND pr.first_commit_at IS NOT NULL
        """,
        nativeQuery = true)

    Page<ChangeLeadTimeRow> findChangeLeadTimeRows(
        @Param("repositoryIds") Collection<UUID> repositoryIds,
        @Param("from") Instant from,
        @Param("to") Instant to,
        Pageable pageable
    );
}

