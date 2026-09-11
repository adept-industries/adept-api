package com.adept.api.integration.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.adept.api.common.domain.IntegrationStatus;
import com.adept.api.common.domain.MembershipRole;
import com.adept.api.common.error.ApiException;
import com.adept.api.common.error.ProblemCode;
import com.adept.api.integration.github.dto.RepositorySettingsOptionsResponse;
import com.adept.api.integration.github.dto.RepositorySettingsOptionsResponse.SettingsOptions;
import com.adept.api.workspace.Membership;

class RepositorySettingsOptionsServiceTest {
    private final GitRepositoryRepository repositories = mock(GitRepositoryRepository.class);
    private final GithubRepositoryOptionsClient client = mock(GithubRepositoryOptionsClient.class);
    private final RepositorySettingsOptionsService service = new RepositorySettingsOptionsService(repositories, client);
    private final UUID workspaceId = UUID.randomUUID();
    private final UUID repositoryId = UUID.randomUUID();
    private final Membership manager = new Membership();
    private final GithubIntegration integration = new GithubIntegration();
    private final GitRepository repository = new GitRepository();
    private final SettingsOptions options = new SettingsOptions(List.of("main"), true, null);
    private final RepositorySettingsOptionsResponse result = new RepositorySettingsOptionsResponse(options, options, options);

    @BeforeEach
    void setUp() {
        manager.setRole(MembershipRole.MANAGER);
        integration.setInstallationId(42);
        repository.setGithubIntegration(integration);
        repository.setOwnerLogin("acme");
        repository.setName("app");
        when(repositories.findForSettingsDiscovery(repositoryId, workspaceId)).thenReturn(Optional.of(repository));
        when(client.discover(42, "acme", "app")).thenReturn(result);
    }

    @Test
    void cachesSuccessButRechecksRepositoryAndInstallationAccess() {
        assertThat(service.get(workspaceId, repositoryId, manager)).isEqualTo(result);
        assertThat(service.get(workspaceId, repositoryId, manager)).isEqualTo(result);
        verify(client).discover(42, "acme", "app");
        verify(repositories, times(2)).findForSettingsDiscovery(repositoryId, workspaceId);
        integration.setStatus(IntegrationStatus.SUSPENDED);
        assertThat(service.get(workspaceId, repositoryId, manager).complete()).isFalse();
        verifyNoMoreInteractions(client);
    }

    @Test
    void rejectsLeadEvenWhenManagerPreviouslyPopulatedCache() {
        service.get(workspaceId, repositoryId, manager);
        manager.setRole(MembershipRole.LEAD);
        assertThatThrownBy(() -> service.get(workspaceId, repositoryId, manager))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ProblemCode.MANAGER_REQUIRED));
        verify(client).discover(42, "acme", "app");
    }

    @Test
    void unknownOrOtherWorkspaceRepositoryNeverCallsGithub() {
        assertThatThrownBy(() -> service.get(UUID.randomUUID(), repositoryId, manager))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ProblemCode.REPOSITORY_NOT_FOUND));
        verifyNoInteractions(client);
    }

    @Test
    void partialResultsCanBeRetriedAndRenamesInvalidateCache() {
        when(client.discover(42, "acme", "app"))
            .thenReturn(RepositorySettingsOptionsResponse.unavailable("Try again"), result);
        assertThat(service.get(workspaceId, repositoryId, manager).complete()).isFalse();
        assertThat(service.get(workspaceId, repositoryId, manager)).isEqualTo(result);
        verify(client, times(2)).discover(42, "acme", "app");
        repository.setName("renamed");
        when(client.discover(42, "acme", "renamed")).thenReturn(result);
        service.get(workspaceId, repositoryId, manager);
        verify(client).discover(42, "acme", "renamed");
    }
}
