package com.adept.api.pullrequest;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PullRequestFeatureRepository extends JpaRepository<PullRequestFeature, UUID> {

    Optional<PullRequestFeature> findByPullRequestIdAndFeatureSchemaVersion(
        UUID pullRequestId,
        String featureSchemaVersion
    );
}
