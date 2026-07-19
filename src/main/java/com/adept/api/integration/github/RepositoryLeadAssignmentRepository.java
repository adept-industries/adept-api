package com.adept.api.integration.github;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface RepositoryLeadAssignmentRepository
    extends JpaRepository<RepositoryLeadAssignment, UUID> {
    Optional<RepositoryLeadAssignment> findByRepositoryId(UUID repositoryId);
    boolean existsByRepositoryIdAndLeadMembershipId(UUID repositoryId, UUID membershipId);
}
