package com.adept.api.risk;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.UUID;

public interface RiskPredictionRepository
    extends JpaRepository<RiskPrediction, UUID> {

    Page<RiskPrediction> findAllByWorkspaceIdAndRepositoryIdIn(
        UUID workspaceId,
        Collection<UUID> repositoryIds,
        Pageable pageable
    );
}
