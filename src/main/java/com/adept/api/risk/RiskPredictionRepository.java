package com.adept.api.risk;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.adept.api.common.domain.RiskLevel;

public interface RiskPredictionRepository
    extends JpaRepository<RiskPrediction, UUID> {

    @Query(
        value = """
            select prediction
            from RiskPrediction prediction
            join fetch prediction.pullRequest
            join fetch prediction.repository
            join fetch prediction.feature feature
            where prediction.workspace.id = :workspaceId
              and prediction.repository.id in :repositoryIds
              and prediction.modelName = :modelName
              and prediction.modelVersion = :modelVersion
              and feature.featureSchemaVersion = :featureSchemaVersion
            """,
        countQuery = """
            select count(prediction)
            from RiskPrediction prediction
            join prediction.feature feature
            where prediction.workspace.id = :workspaceId
              and prediction.repository.id in :repositoryIds
              and prediction.modelName = :modelName
              and prediction.modelVersion = :modelVersion
              and feature.featureSchemaVersion = :featureSchemaVersion
            """
    )
    Page<RiskPrediction> findAllCurrentByScope(
        @Param("workspaceId") UUID workspaceId,
        @Param("repositoryIds") Collection<UUID> repositoryIds,
        @Param("modelName") String modelName,
        @Param("modelVersion") String modelVersion,
        @Param("featureSchemaVersion") String featureSchemaVersion,
        Pageable pageable
    );

    @Query("""
        select prediction
        from RiskPrediction prediction
        join fetch prediction.pullRequest
        join fetch prediction.repository
        join fetch prediction.feature feature
        where prediction.pullRequest.id = :pullRequestId
          and prediction.modelName = :modelName
          and prediction.modelVersion = :modelVersion
          and feature.featureSchemaVersion = :featureSchemaVersion
        """)
    Optional<RiskPrediction> findCurrentForPullRequest(
        @Param("pullRequestId") UUID pullRequestId,
        @Param("modelName") String modelName,
        @Param("modelVersion") String modelVersion,
        @Param("featureSchemaVersion") String featureSchemaVersion
    );

    @Query(
        value = """
            select prediction
            from RiskPrediction prediction
            join fetch prediction.pullRequest pullRequest
            join fetch prediction.repository
            join fetch prediction.feature feature
            where prediction.workspace.id = :workspaceId
              and prediction.repository.id in :repositoryIds
              and pullRequest.state = com.adept.api.common.domain.PullRequestState.OPEN
              and prediction.modelName = :modelName
              and prediction.modelVersion = :modelVersion
              and feature.featureSchemaVersion = :featureSchemaVersion
              and prediction.riskLevel in :riskLevels
              and pullRequest.openedAt <= :openedBefore
            order by prediction.riskScore desc, pullRequest.openedAt asc, pullRequest.id asc
            """,
        countQuery = """
            select count(prediction)
            from RiskPrediction prediction
            join prediction.pullRequest pullRequest
            join prediction.feature feature
            where prediction.workspace.id = :workspaceId
              and prediction.repository.id in :repositoryIds
              and pullRequest.state = com.adept.api.common.domain.PullRequestState.OPEN
              and prediction.modelName = :modelName
              and prediction.modelVersion = :modelVersion
              and feature.featureSchemaVersion = :featureSchemaVersion
              and prediction.riskLevel in :riskLevels
              and pullRequest.openedAt <= :openedBefore
            """
    )
    Page<RiskPrediction> findCurrentOpenByScope(
        @Param("workspaceId") UUID workspaceId,
        @Param("repositoryIds") Collection<UUID> repositoryIds,
        @Param("modelName") String modelName,
        @Param("modelVersion") String modelVersion,
        @Param("featureSchemaVersion") String featureSchemaVersion,
        @Param("riskLevels") Collection<RiskLevel> riskLevels,
        @Param("openedBefore") Instant openedBefore,
        Pageable pageable
    );
}
