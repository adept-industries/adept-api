package com.adept.api.security;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.adept.api.common.domain.MembershipRole;
import com.adept.api.common.error.NotFoundException;
import com.adept.api.integration.github.GitRepository;
import com.adept.api.integration.github.GitRepositoryRepository;
import com.adept.api.integration.github.RepositoryLeadAssignmentRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RepositoryScopeServiceTest {

    @Mock
    private GitRepositoryRepository gitRepositoryRepository;

    @Mock
    private RepositoryLeadAssignmentRepository repositoryLeadAssignmentRepository;

    @InjectMocks
    private RepositoryScopeService repositoryScopeService;

    private UUID workspaceId;
    private UUID membershipId;
    private UUID repositoryId;
    private AuthenticatedPrincipal managerPrincipal;
    private AuthenticatedPrincipal leadPrincipal;
    private GitRepository activeRepository;

    @BeforeEach
    void setUp() {
        workspaceId = UUID.randomUUID();
        membershipId = UUID.randomUUID();
        repositoryId = UUID.randomUUID();
        managerPrincipal = principal(UUID.randomUUID(), MembershipRole.MANAGER);
        leadPrincipal = principal(membershipId, MembershipRole.LEAD);

        activeRepository = new GitRepository();
        activeRepository.setId(repositoryId);
        activeRepository.setTrackingEnabled(true);
        activeRepository.setArchived(false);
    }

    @Test
    void managerCanReadAndManageARepositoryInTheCurrentWorkspace() {
        activeRepository.setArchived(true);
        when(gitRepositoryRepository.findByIdAndWorkspaceId(repositoryId, workspaceId))
            .thenReturn(Optional.of(activeRepository));

        assertThat(repositoryScopeService.requireReadableRepository(managerPrincipal, repositoryId))
            .isSameAs(activeRepository);
        assertThat(repositoryScopeService.requireManageableRepository(managerPrincipal, repositoryId))
            .isSameAs(activeRepository);
    }

    @Test
    void assignedCoLeadsCanReadAndUseAssignedScope() {
        UUID firstMembershipId = UUID.randomUUID();
        UUID secondMembershipId = UUID.randomUUID();
        AuthenticatedPrincipal firstLead = principal(firstMembershipId, MembershipRole.LEAD);
        AuthenticatedPrincipal secondLead = principal(secondMembershipId, MembershipRole.LEAD);
        when(gitRepositoryRepository.findByIdAndWorkspaceId(repositoryId, workspaceId))
            .thenReturn(Optional.of(activeRepository));
        when(repositoryLeadAssignmentRepository.existsByRepositoryIdAndLeadMembershipId(
            repositoryId,
            firstMembershipId
        )).thenReturn(true);
        when(repositoryLeadAssignmentRepository.existsByRepositoryIdAndLeadMembershipId(
            repositoryId,
            secondMembershipId
        )).thenReturn(true);

        assertThat(repositoryScopeService.requireReadableRepository(firstLead, repositoryId))
            .isSameAs(activeRepository);
        assertThat(repositoryScopeService.requireReadableRepository(secondLead, repositoryId))
            .isSameAs(activeRepository);
        assertThat(repositoryScopeService.requireAssignedRepository(firstLead, repositoryId))
            .isSameAs(activeRepository);
    }

    @Test
    void unassignedOrInactiveRepositoriesAreHiddenFromLeads() {
        when(gitRepositoryRepository.findByIdAndWorkspaceId(repositoryId, workspaceId))
            .thenReturn(Optional.of(activeRepository));
        when(repositoryLeadAssignmentRepository.existsByRepositoryIdAndLeadMembershipId(repositoryId, membershipId))
            .thenReturn(false);

        assertThatThrownBy(() -> repositoryScopeService.requireReadableRepository(leadPrincipal, repositoryId))
            .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> repositoryScopeService.requireAssignedRepository(leadPrincipal, repositoryId))
            .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> repositoryScopeService.requireManageableRepository(leadPrincipal, repositoryId))
            .isInstanceOf(NotFoundException.class);

        activeRepository.setArchived(true);
        assertThatThrownBy(() -> repositoryScopeService.requireReadableRepository(leadPrincipal, repositoryId))
            .isInstanceOf(NotFoundException.class);
        activeRepository.setArchived(false);
        activeRepository.setTrackingEnabled(false);
        assertThatThrownBy(() -> repositoryScopeService.requireReadableRepository(leadPrincipal, repositoryId))
            .isInstanceOf(NotFoundException.class);
    }

    @Test
    void crossWorkspaceLookupReturnsNotFoundWithoutLeakingRepositoryState() {
        UUID otherWorkspaceId = UUID.randomUUID();
        AuthenticatedPrincipal otherWorkspaceManager = new AuthenticatedPrincipal(
            UUID.randomUUID(),
            UUID.randomUUID(),
            otherWorkspaceId,
            MembershipRole.MANAGER,
            1
        );
        when(gitRepositoryRepository.findByIdAndWorkspaceId(repositoryId, otherWorkspaceId))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() -> repositoryScopeService.requireReadableRepository(
            otherWorkspaceManager,
            repositoryId
        )).isInstanceOf(NotFoundException.class);
    }

    private AuthenticatedPrincipal principal(UUID principalMembershipId, MembershipRole role) {
        return new AuthenticatedPrincipal(
            UUID.randomUUID(),
            principalMembershipId,
            workspaceId,
            role,
            1
        );
    }
}
