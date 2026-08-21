package com.adept.api.pullrequest;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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
}
