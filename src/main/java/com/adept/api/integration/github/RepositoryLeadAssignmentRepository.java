package com.adept.api.integration.github;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RepositoryLeadAssignmentRepository
    extends JpaRepository<RepositoryLeadAssignment, UUID> {
    List<RepositoryLeadAssignment> findAllByRepositoryId(UUID repositoryId);
    List<RepositoryLeadAssignment> findAllByInvitationId(UUID invitationId);
    List<RepositoryLeadAssignment> findAllByLeadMembershipId(UUID leadMembershipId);
    boolean existsByRepositoryIdAndLeadMembershipId(UUID repositoryId, UUID membershipId);
    boolean existsByRepositoryIdAndInvitationId(UUID repositoryId, UUID invitationId);
    Optional<RepositoryLeadAssignment> findByRepositoryIdAndInvitationId(UUID repositoryId, UUID invitationId);
}
