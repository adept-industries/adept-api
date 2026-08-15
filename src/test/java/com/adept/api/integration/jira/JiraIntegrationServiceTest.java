package com.adept.api.integration.jira;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.adept.api.audit.AuditAction;
import com.adept.api.audit.AuditService;
import com.adept.api.common.domain.ExternalProvider;
import com.adept.api.common.domain.MembershipRole;
import com.adept.api.common.error.ApiException;
import com.adept.api.common.error.ProblemCode;
import com.adept.api.config.AppProperties;
import com.adept.api.crypto.IntegrationEncryptionService;
import com.adept.api.crypto.SecureTokenGenerator;
import com.adept.api.integration.common.IntegrationOauthState;
import com.adept.api.integration.common.IntegrationOauthStateService;
import com.adept.api.integration.github.GitRepository;
import com.adept.api.integration.github.GitRepositoryRepository;
import com.adept.api.integration.jira.dto.JiraConnectUrlResponse;
import com.adept.api.user.User;
import com.adept.api.workspace.Membership;
import com.adept.api.workspace.Workspace;
import com.adept.api.workspace.WorkspaceRepository;

@ExtendWith(MockitoExtension.class)
class JiraIntegrationServiceTest {

    @Mock private JiraIntegrationRepository jiraIntegrationRepository;
    @Mock private JiraProjectRepository jiraProjectRepository;
    @Mock private RepositoryJiraProjectRepository repositoryJiraProjectRepository;
    @Mock private GitRepositoryRepository gitRepositoryRepository;
    @Mock private WorkspaceRepository workspaceRepository;
    @Mock private IntegrationOauthStateService oauthStateService;
    @Mock private JiraOAuthClient jiraOAuthClient;
    @Mock private JiraApiClient jiraApiClient;
    @Mock private SecureTokenGenerator tokenGenerator;
    @Mock private AuditService auditService;

    private Clock clock;
    private IntegrationEncryptionService encryptionService;
    private JiraIntegrationService service;

    private Workspace testWorkspace;
    private Membership managerMembership;
    private Membership leadMembership;
    private User testUser;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2026-08-15T10:00:00Z"), ZoneOffset.UTC);
        SecureRandom random = new SecureRandom();
        byte[] keyBytes = new byte[32];
        random.nextBytes(keyBytes);
        encryptionService = new IntegrationEncryptionService(
            1,
            Map.of(1, new SecretKeySpec(keyBytes, "AES")),
            random
        );

        AppProperties properties = new AppProperties(
            URI.create("http://localhost:3000"),
            URI.create("http://localhost:8080"),
            "test@adept.local",
            new AppProperties.Jwt("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", "adept-api", "adept-frontend", java.time.Duration.ofMinutes(15)),
            new AppProperties.Auth(12, java.time.Duration.ofHours(24), java.time.Duration.ofHours(1), java.time.Duration.ofMinutes(5),
                new AppProperties.RateLimit(30000, java.time.Duration.ofMinutes(15), 10, java.time.Duration.ofMinutes(15), 3, java.time.Duration.ofHours(1), 5, java.time.Duration.ofHours(1), 10, java.time.Duration.ofMinutes(15), 10, java.time.Duration.ofMinutes(15), 100000)),
            new AppProperties.RefreshToken(java.time.Duration.ofDays(7), "adept_refresh", false, "Strict"),
            "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBA=",
            new AppProperties.IntegrationEncryption(1, Map.of(1, "CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCA=")),
            new AppProperties.Github(true, "123", "adept-test", "dGVzdC1vbmx5", "secret"),
            new AppProperties.Jira(true, "client-123", "secret-123", URI.create("http://localhost:8080/callback")),
            new AppProperties.Engine(URI.create("http://localhost:8000"), "internal-token")
        );

        service = new JiraIntegrationService(
            properties,
            jiraIntegrationRepository,
            jiraProjectRepository,
            repositoryJiraProjectRepository,
            gitRepositoryRepository,
            workspaceRepository,
            oauthStateService,
            jiraOAuthClient,
            jiraApiClient,
            encryptionService,
            tokenGenerator,
            auditService,
            clock
        );

        testWorkspace = new Workspace();
        testWorkspace.setId(UUID.randomUUID());
        testWorkspace.setName("Test Workspace");

        testUser = new User();
        testUser.setId(UUID.randomUUID());
        testUser.setEmail("manager@example.com");

        managerMembership = new Membership();
        managerMembership.setId(UUID.randomUUID());
        managerMembership.setWorkspace(testWorkspace);
        managerMembership.setUser(testUser);
        managerMembership.setRole(MembershipRole.MANAGER);

        leadMembership = new Membership();
        leadMembership.setId(UUID.randomUUID());
        leadMembership.setWorkspace(testWorkspace);
        leadMembership.setUser(testUser);
        leadMembership.setRole(MembershipRole.LEAD);
    }

    @Test
    @DisplayName("createConnectUrl requires MANAGER role")
    void createConnectUrlRequiresManager() {
        assertThatThrownBy(() -> service.createConnectUrl(testWorkspace.getId(), leadMembership))
            .isInstanceOf(ApiException.class)
            .matches(e -> ((ApiException) e).code() == ProblemCode.MANAGER_REQUIRED);
    }

    @Test
    @DisplayName("createConnectUrl builds Jira authorization URL with PKCE")
    void createConnectUrlSuccess() {
        when(workspaceRepository.findById(testWorkspace.getId())).thenReturn(Optional.of(testWorkspace));
        when(tokenGenerator.generate()).thenReturn("abcdef1234567890abcdef1234567890");
        when(oauthStateService.issueState(eq(ExternalProvider.JIRA), eq(testWorkspace), eq(managerMembership), any(), any()))
            .thenReturn(new IntegrationOauthStateService.IssuedOauthState("raw-jira-state", new IntegrationOauthState()));
        when(jiraOAuthClient.buildAuthorizationUrl(eq("raw-jira-state"), any()))
            .thenReturn("https://auth.atlassian.com/authorize?state=raw-jira-state");

        JiraConnectUrlResponse response = service.createConnectUrl(testWorkspace.getId(), managerMembership);

        assertThat(response.state()).isEqualTo("raw-jira-state");
        assertThat(response.url()).contains("https://auth.atlassian.com/authorize");
    }

    @Test
    @DisplayName("mapProjectsToRepository rejects projects from a different workspace")
    void mapProjectsRejectsCrossWorkspace() {
        GitRepository repository = new GitRepository();
        repository.setId(UUID.randomUUID());
        repository.setWorkspace(testWorkspace);

        when(gitRepositoryRepository.findByIdAndWorkspaceId(repository.getId(), testWorkspace.getId()))
            .thenReturn(Optional.of(repository));

        UUID otherWorkspaceProjectId = UUID.randomUUID();
        when(jiraProjectRepository.findAllByIdInAndWorkspaceId(List.of(otherWorkspaceProjectId), testWorkspace.getId()))
            .thenReturn(List.of()); // None found in this workspace

        assertThatThrownBy(() -> service.mapProjectsToRepository(
            testWorkspace.getId(),
            repository.getId(),
            List.of(otherWorkspaceProjectId),
            managerMembership
        ))
            .isInstanceOf(ApiException.class)
            .matches(e -> ((ApiException) e).code() == ProblemCode.JIRA_PROJECT_NOT_FOUND);
    }
}
