package com.adept.api.security;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.adept.api.common.domain.MembershipRole;
import com.adept.api.common.error.NotFoundException;
import com.adept.api.common.error.ProblemCode;
import com.adept.api.integration.github.GitRepository;
import com.adept.api.integration.github.GitRepositoryRepository;
import com.adept.api.integration.github.RepositoryLeadAssignmentRepository;

@Service
@Transactional(readOnly = true)
public class RepositoryScopeService {

    private final GitRepositoryRepository gitRepositoryRepository;
    private final RepositoryLeadAssignmentRepository repositoryLeadAssignmentRepository;

    public RepositoryScopeService(
            GitRepositoryRepository gitRepositoryRepository,
            RepositoryLeadAssignmentRepository repositoryLeadAssignmentRepository) {
        this.gitRepositoryRepository = gitRepositoryRepository;
        this.repositoryLeadAssignmentRepository = repositoryLeadAssignmentRepository;
    }

    public GitRepository requireReadableRepository(AuthenticatedPrincipal principal, UUID repositoryId) {
        if (principal == null || principal.workspaceId() == null || repositoryId == null) {
            throw new NotFoundException(ProblemCode.REPOSITORY_NOT_FOUND);
        }

        GitRepository repository = gitRepositoryRepository.findByIdAndWorkspaceId(repositoryId, principal.workspaceId())
            .orElseThrow(() -> new NotFoundException(ProblemCode.REPOSITORY_NOT_FOUND));

        if (principal.role() == MembershipRole.MANAGER) {
            return repository;
        }

        // LEAD / CO-LEAD check:
        // Must be trackingEnabled = true, archived = false, and assigned to principal membership
        if (!repository.isTrackingEnabled() || repository.isArchived()) {
            throw new NotFoundException(ProblemCode.REPOSITORY_NOT_FOUND);
        }

        boolean assigned = repositoryLeadAssignmentRepository.existsByRepositoryIdAndLeadMembershipId(
            repositoryId,
            principal.membershipId()
        );

        if (!assigned) {
            throw new NotFoundException(ProblemCode.REPOSITORY_NOT_FOUND);
        }

        return repository;
    }

    public GitRepository requireManageableRepository(AuthenticatedPrincipal principal, UUID repositoryId) {
        if (principal == null || principal.workspaceId() == null || repositoryId == null) {
            throw new NotFoundException(ProblemCode.REPOSITORY_NOT_FOUND);
        }

        if (principal.role() != MembershipRole.MANAGER) {
            throw new NotFoundException(ProblemCode.REPOSITORY_NOT_FOUND);
        }

        return gitRepositoryRepository.findByIdAndWorkspaceId(repositoryId, principal.workspaceId())
            .orElseThrow(() -> new NotFoundException(ProblemCode.REPOSITORY_NOT_FOUND));
    }

    public GitRepository requireAssignedRepository(AuthenticatedPrincipal principal, UUID repositoryId) {
        if (principal == null || principal.workspaceId() == null || repositoryId == null) {
            throw new NotFoundException(ProblemCode.REPOSITORY_NOT_FOUND);
        }

        GitRepository repository = gitRepositoryRepository.findByIdAndWorkspaceId(repositoryId, principal.workspaceId())
            .orElseThrow(() -> new NotFoundException(ProblemCode.REPOSITORY_NOT_FOUND));

        if (!repository.isTrackingEnabled() || repository.isArchived()) {
            throw new NotFoundException(ProblemCode.REPOSITORY_NOT_FOUND);
        }

        boolean assigned = repositoryLeadAssignmentRepository.existsByRepositoryIdAndLeadMembershipId(
            repositoryId,
            principal.membershipId()
        );

        if (!assigned) {
            throw new NotFoundException(ProblemCode.REPOSITORY_NOT_FOUND);
        }

        return repository;
    }
}
