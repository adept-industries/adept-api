package com.adept.api.risk;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RiskPredictionRepository
    extends JpaRepository<RiskPrediction, UUID> {

    Page<RiskPrediction> findAllByWorkspaceIdAndRepositoryIdIn(
        UUID workspaceId,
        Collection<UUID> repositoryIds,
        Pageable pageable
    );

    Optional<RiskPrediction> findFirstByPullRequestIdOrderByPredictedAtDesc(UUID pullRequestId);

    @Query("""
        select rp from RiskPrediction rp
        where rp.repository.id = :repositoryId
          and rp.pullRequest.state = 'OPEN'
          and rp.riskLevel in :riskLevels
          and rp.predictedAt = (
              select max(innerRp.predictedAt)
              from RiskPrediction innerRp
              where innerRp.pullRequest.id = rp.pullRequest.id
          )
        order by rp.riskScore desc
        """)
    List<RiskPrediction> findLatestRiskyByRepository(
        @Param("repositoryId") UUID repositoryId,
        @Param("riskLevels") Collection<com.adept.api.common.domain.RiskLevel> riskLevels
    );
}
