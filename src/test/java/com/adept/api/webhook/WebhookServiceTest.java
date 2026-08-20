package com.adept.api.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.adept.api.common.domain.IntegrationStatus;
import com.adept.api.common.domain.ProcessingJobType;
import com.adept.api.common.domain.WebhookStatus;
import com.adept.api.config.AppProperties;
import com.adept.api.crypto.TokenHasher;
import com.adept.api.integration.github.GitRepository;
import com.adept.api.integration.github.GitRepositoryRepository;
import com.adept.api.integration.github.GithubIntegration;
import com.adept.api.integration.github.GithubIntegrationRepository;
import com.adept.api.integration.jira.JiraIntegration;
import com.adept.api.integration.jira.JiraIntegrationRepository;
import com.adept.api.integration.jira.JiraProjectRepository;
import com.adept.api.job.ProcessingJob;
import com.adept.api.job.ProcessingJobRepository;
import com.adept.api.workspace.Workspace;

import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class WebhookServiceTest {

    private static final String GITHUB_WEBHOOK_SECRET = "test-webhook-secret";
    private static final long INSTALLATION_ID = 12345L;

    @Mock private AppProperties properties;
    @Mock private RawWebhookEventRepository rawWebhookEventRepository;
    @Mock private ProcessingJobRepository processingJobRepository;
    @Mock private GithubIntegrationRepository githubIntegrationRepository;
    @Mock private GitRepositoryRepository gitRepositoryRepository;
    @Mock private JiraIntegrationRepository jiraIntegrationRepository;
    @Mock private JiraProjectRepository jiraProjectRepository;
    @Mock private TokenHasher tokenHasher;

    private WebhookService service;
    private GithubIntegration suspendedIntegration;

    @BeforeEach
    void setUp() {
        lenient().when(properties.github()).thenReturn(new AppProperties.Github(
            true,
            "1",
            "adept-test",
            "private-key",
            GITHUB_WEBHOOK_SECRET
        ));

        service = new WebhookService(
            properties,
            rawWebhookEventRepository,
            processingJobRepository,
            githubIntegrationRepository,
            gitRepositoryRepository,
            jiraIntegrationRepository,
            jiraProjectRepository,
            tokenHasher,
            new JsonMapper()
        );

        Workspace workspace = new Workspace();
        workspace.setId(UUID.randomUUID());
        suspendedIntegration = new GithubIntegration();
        suspendedIntegration.setId(UUID.randomUUID());
        suspendedIntegration.setWorkspace(workspace);
        suspendedIntegration.setInstallationId(INSTALLATION_ID);
        suspendedIntegration.setStatus(IntegrationStatus.SUSPENDED);

        lenient().when(rawWebhookEventRepository.existsBySourceAndDeliveryId(any(), any()))
            .thenReturn(false);
        lenient().when(githubIntegrationRepository.findByInstallationId(INSTALLATION_ID))
            .thenReturn(Optional.of(suspendedIntegration));
        lenient().when(rawWebhookEventRepository.save(any(RawWebhookEvent.class)))
            .thenAnswer(invocation -> {
                RawWebhookEvent event = invocation.getArgument(0);
                event.setId(UUID.randomUUID());
                return event;
            });
    }

    @Test
    void queuesInstallationLifecycleEventsForASuspendedIntegration() throws Exception {
        byte[] body = githubPayload("installation", "unsuspend", null);

        boolean accepted = service.ingestGithubWebhook(
            body,
            sign(body),
            "delivery-unsuspend",
            "installation",
            Map.of()
        );

        assertThat(accepted).isTrue();
        ArgumentCaptor<RawWebhookEvent> eventCaptor =
            ArgumentCaptor.forClass(RawWebhookEvent.class);
        verify(rawWebhookEventRepository).save(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getWorkspace())
            .isSameAs(suspendedIntegration.getWorkspace());
        assertThat(eventCaptor.getValue().getStatus()).isEqualTo(WebhookStatus.QUEUED);

        ArgumentCaptor<ProcessingJob> jobCaptor = ArgumentCaptor.forClass(ProcessingJob.class);
        verify(processingJobRepository).save(jobCaptor.capture());
        assertThat(jobCaptor.getValue().getJobType())
            .isEqualTo(ProcessingJobType.PROCESS_GITHUB_EVENT);
        assertThat(jobCaptor.getValue().getWorkspace())
            .isSameAs(suspendedIntegration.getWorkspace());
    }

    @Test
    void ignoresOrdinaryEventsForASuspendedIntegration() throws Exception {
        byte[] body = githubPayload("pull_request", "opened", 67890L);

        boolean accepted = service.ingestGithubWebhook(
            body,
            sign(body),
            "delivery-pr",
            "pull_request",
            Map.of()
        );

        assertThat(accepted).isFalse();
        ArgumentCaptor<RawWebhookEvent> eventCaptor =
            ArgumentCaptor.forClass(RawWebhookEvent.class);
        verify(rawWebhookEventRepository).save(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getWorkspace())
            .isSameAs(suspendedIntegration.getWorkspace());
        assertThat(eventCaptor.getValue().getStatus()).isEqualTo(WebhookStatus.IGNORED);
        verify(processingJobRepository, never()).save(any());
        verify(gitRepositoryRepository, never())
            .findByGithubIntegrationIdAndGithubRepoId(any(), anyLong());
    }

    @Test
    void ignoresOrdinaryDataEventsForATrackingDisabledRepository() throws Exception {
        suspendedIntegration.setStatus(IntegrationStatus.ACTIVE);
        GitRepository repository = trackingDisabledRepository();
        when(gitRepositoryRepository.findByGithubIntegrationIdAndGithubRepoId(
                suspendedIntegration.getId(), 67890L))
            .thenReturn(Optional.of(repository));
        byte[] body = githubPayload("pull_request", "opened", 67890L);

        boolean accepted = service.ingestGithubWebhook(
            body,
            sign(body),
            "delivery-disabled-pr",
            "pull_request",
            Map.of()
        );

        assertThat(accepted).isFalse();
        ArgumentCaptor<RawWebhookEvent> eventCaptor =
            ArgumentCaptor.forClass(RawWebhookEvent.class);
        verify(rawWebhookEventRepository).save(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getRepository()).isSameAs(repository);
        assertThat(eventCaptor.getValue().getStatus()).isEqualTo(WebhookStatus.IGNORED);
        verify(processingJobRepository, never()).save(any());
    }

    @Test
    void queuesRepositoryLifecycleEventsForATrackingDisabledRepository() throws Exception {
        suspendedIntegration.setStatus(IntegrationStatus.ACTIVE);
        GitRepository repository = trackingDisabledRepository();
        when(gitRepositoryRepository.findByGithubIntegrationIdAndGithubRepoId(
                suspendedIntegration.getId(), 67890L))
            .thenReturn(Optional.of(repository));
        byte[] body = githubPayload("repository", "renamed", 67890L);

        boolean accepted = service.ingestGithubWebhook(
            body,
            sign(body),
            "delivery-disabled-repository",
            "repository",
            Map.of()
        );

        assertThat(accepted).isTrue();
        ArgumentCaptor<RawWebhookEvent> eventCaptor =
            ArgumentCaptor.forClass(RawWebhookEvent.class);
        verify(rawWebhookEventRepository).save(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getRepository()).isSameAs(repository);
        assertThat(eventCaptor.getValue().getStatus()).isEqualTo(WebhookStatus.QUEUED);
        verify(processingJobRepository).save(any(ProcessingJob.class));
    }

    @Test
    void dropsUntrackedJiraIssuePayloadBeforePersistence() {
        String rawToken = "A".repeat(43);
        String tokenHash = "b".repeat(64);
        JiraIntegration integration = new JiraIntegration();
        integration.setId(UUID.randomUUID());
        integration.setWorkspace(suspendedIntegration.getWorkspace());
        integration.setStatus(IntegrationStatus.ACTIVE);
        integration.setWebhookTokenHash(tokenHash);
        when(jiraIntegrationRepository.findById(integration.getId()))
            .thenReturn(Optional.of(integration));
        when(tokenHasher.hashJiraWebhookToken(rawToken)).thenReturn(tokenHash);
        when(jiraProjectRepository
                .existsByJiraIntegrationIdAndJiraProjectIdAndTrackingEnabledTrue(
                    integration.getId(), "10000"))
            .thenReturn(false);

        boolean accepted = service.ingestJiraWebhook(
            integration.getId(),
            rawToken,
            """
                {
                  "webhookEvent": "jira:issue_updated",
                  "issue": {
                    "id": "sensitive-untracked-issue",
                    "fields": {"project": {"id": "10000"}}
                  }
                }
                """.getBytes(StandardCharsets.UTF_8),
            Map.of()
        );

        assertThat(accepted).isFalse();
        verify(rawWebhookEventRepository, never()).save(any());
        verify(processingJobRepository, never()).save(any());
    }

    private GitRepository trackingDisabledRepository() {
        GitRepository repository = new GitRepository();
        repository.setId(UUID.randomUUID());
        repository.setWorkspace(suspendedIntegration.getWorkspace());
        repository.setGithubIntegration(suspendedIntegration);
        repository.setGithubRepoId(67890L);
        repository.setTrackingEnabled(false);
        return repository;
    }

    private byte[] githubPayload(String eventType, String action, Long repositoryId) {
        String repository = repositoryId == null
            ? ""
            : ",\"repository\":{\"id\":" + repositoryId + "}";
        return ("{\"action\":\"" + action + "\",\"event\":\"" + eventType
            + "\",\"installation\":{\"id\":" + INSTALLATION_ID + "}"
            + repository + "}").getBytes(StandardCharsets.UTF_8);
    }

    private String sign(byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(
            GITHUB_WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8),
            "HmacSHA256"
        ));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
    }
}
