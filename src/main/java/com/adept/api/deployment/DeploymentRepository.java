package com.adept.api.deployment;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.adept.api.common.domain.DeploymentSource;

public interface DeploymentRepository extends JpaRepository<Deployment, UUID> {

    Optional<Deployment> findByRepositoryIdAndSourceAndExternalDeploymentId(
        UUID repositoryId,
        DeploymentSource source,
        String externalDeploymentId
    );
}
