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
    private GitRepository activeRepo;

    @BeforeEach
    void setUp() {
        workspaceId = UUID.randomUUID();
        membershipId = UUID.randomUUID();
        repositoryId = UUID.randomUUID();

        managerPrincipal = new AuthenticatedPrincipal(UUID.randomUUID(), membershipId, workspaceId, MembershipRole.MANAGER, 1);
        leadPrincipal = new AuthenticatedPrincipal(UUID.randomUUID(), membershipId, workspaceId, MembershipRole.LEAD, 1);

        activeRepo = new GitRepository();
        activeRepo.setId(repositoryId);
        activeRepo.setTrackingEnabled(true);
        activeRepo.setArchived(false);
    }

    @Test
    void managerCanReadAnyRepositoryInWorkspace() {
        when(gitRepositoryRepository.findByIdAndWorkspaceId(repositoryId, workspaceId))
            .thenReturn(Optional.of(activeRepo));

        GitRepository result = repositoryScopeService.requireReadableRepository(managerPrincipal, repositoryId);
        assertThat(result).isEqualTo(activeRepo);
    }

    @Test
    void managerCanManageAnyRepositoryInWorkspace() {
        when(gitRepositoryRepository.findByIdAndWorkspaceId(repositoryId, workspaceId))
            .thenReturn(Optional.of(activeRepo));

        GitRepository result = repositoryScopeService.requireManageableRepository(managerPrincipal, repositoryId);
        assertThat(result).isEqualTo(activeRepo);
    }

    @Test
    void leadCanReadAssignedActiveRepository() {
        when(gitRepositoryRepository.findByIdAndWorkspaceId(repositoryId, workspaceId))
            .thenReturn(Optional.of(activeRepo));
        when(repositoryLeadAssignmentRepository.existsByRepositoryIdAndLeadMembershipId(repositoryId, membershipId))
            .thenReturn(true);

        GitRepository result = repositoryScopeService.requireReadableRepository(leadPrincipal, repositoryId);
        assertThat(result).isEqualTo(activeRepo);
    }

    @Test
    void twoDistinctLeadsAssignedToSameRepositoryCanBothAccessIt() {
        UUID lead1MemId = UUID.randomUUID();
        UUID lead2MemId = UUID.randomUUID();

        AuthenticatedPrincipal lead1 = new AuthenticatedPrincipal(UUID.randomUUID(), lead1MemId, workspaceId, MembershipRole.LEAD, 1);
        AuthenticatedPrincipal lead2 = new AuthenticatedPrincipal(UUID.randomUUID(), lead2MemId, workspaceId, MembershipRole.LEAD, 1);

        when(gitRepositoryRepository.findByIdAndWorkspaceId(repositoryId, workspaceId))
            .thenReturn(Optional.of(activeRepo));
        when(repositoryLeadAssignmentRepository.existsByRepositoryIdAndLeadMembershipId(repositoryId, lead1MemId))
            .thenReturn(true);
        when(repositoryLeadAssignmentRepository.existsByRepositoryIdAndLeadMembershipId(repositoryId, lead2MemId))
            .thenReturn(true);

        GitRepository res1 = repositoryScopeService.requireReadableRepository(lead1, repositoryId);
        GitRepository res2 = repositoryScopeService.requireReadableRepository(lead2, repositoryId);

        assertThat(res1).isEqualTo(activeRepo);
        assertThat(res2).isEqualTo(activeRepo);
    }

    @Test
    void leadCannotReadUnassignedRepository() {
        when(gitRepositoryRepository.findByIdAndWorkspaceId(repositoryId, workspaceId))
            .thenReturn(Optional.of(activeRepo));
        when(repositoryLeadAssignmentRepository.existsByRepositoryIdAndLeadMembershipId(repositoryId, membershipId))
            .thenReturn(false);

        assertThatThrownBy(() -> repositoryScopeService.requireReadableRepository(leadPrincipal, repositoryId))
            .isInstanceOf(NotFoundException.class);
    }

    @Test
    void leadCannotReadArchivedRepository() {
        activeRepo.setArchived(true);
        when(gitRepositoryRepository.findByIdAndWorkspaceId(repositoryId, workspaceId))
            .thenReturn(Optional.of(activeRepo));

        assertThatThrownBy(() -> repositoryScopeService.requireReadableRepository(leadPrincipal, repositoryId))
            .isInstanceOf(NotFoundException.class);
    }

    @Test
    void leadCannotReadUntrackedRepository() {
        activeRepo.setTrackingEnabled(false);
        when(gitRepositoryRepository.findByIdAndWorkspaceId(repositoryId, workspaceId))
            .thenReturn(Optional.of(activeRepo));

        assertThatThrownBy(() -> repositoryScopeService.requireReadableRepository(leadPrincipal, repositoryId))
            .isInstanceOf(NotFoundException.class);
    }

    @Test
    void leadCannotManageRepository() {
        assertThatThrownBy(() -> repositoryScopeService.requireManageableRepository(leadPrincipal, repositoryId))
            .isInstanceOf(NotFoundException.class);
    }

    @Test
    void requireAssignedRepositoryChecksAssignmentAndTracking() {
        when(gitRepositoryRepository.findByIdAndWorkspaceId(repositoryId, workspaceId))
            .thenReturn(Optional.of(activeRepo));
        when(repositoryLeadAssignmentRepository.existsByRepositoryIdAndLeadMembershipId(repositoryId, membershipId))
            .thenReturn(true);

        GitRepository result = repositoryScopeService.requireAssignedRepository(leadPrincipal, repositoryId);
        assertThat(result).isEqualTo(activeRepo);
    }

    @Test
    void crossWorkspaceRepositoryThrowsNotFound() {
        UUID otherWorkspace = UUID.randomUUID();
        AuthenticatedPrincipal otherPrincipal = new AuthenticatedPrincipal(
            UUID.randomUUID(), membershipId, otherWorkspace, MembershipRole.MANAGER, 1
        );

        when(gitRepositoryRepository.findByIdAndWorkspaceId(repositoryId, otherWorkspace))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() -> repositoryScopeService.requireReadableRepository(otherPrincipal, repositoryId))
            .isInstanceOf(NotFoundException.class);
    }
}
