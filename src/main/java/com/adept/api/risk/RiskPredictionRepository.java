package com.adept.api.risk;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
}
