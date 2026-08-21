package com.adept.api.webhook;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import com.adept.api.common.domain.ProcessingJobStatus;
import com.adept.api.common.domain.ProcessingJobType;
import com.adept.api.common.domain.PullRequestState;
import com.adept.api.config.AppProperties;
import com.adept.api.integration.github.GitRepository;
import com.adept.api.integration.github.GitRepositoryRepository;
import com.adept.api.job.ProcessingJob;
import com.adept.api.job.ProcessingJobRepository;
import com.adept.api.pullrequest.PullRequest;
import com.adept.api.pullrequest.PullRequestRepository;
import com.adept.api.workspace.Workspace;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GithubWebhookServiceTest {

    @Mock
    private AppProperties appProperties;

    @Mock
    private AppProperties.Github githubProperties;

    @Mock
    private RawWebhookEventRepository rawWebhookEventRepository;

    @Mock
    private GitRepositoryRepository gitRepositoryRepository;

    @Mock
    private PullRequestRepository pullRequestRepository;

    @Mock
    private ProcessingJobRepository processingJobRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private GithubWebhookService githubWebhookService;

    private GitRepository repository;
    private Workspace workspace;

    @BeforeEach
    void setUp() {
        workspace = new Workspace();
        workspace.setId(UUID.randomUUID());

        repository = new GitRepository();
        repository.setId(UUID.randomUUID());
        repository.setGithubRepoId(123456L);
        repository.setFullName("org/repo");
        repository.setWorkspace(workspace);

        when(appProperties.github()).thenReturn(githubProperties);
        when(githubProperties.webhookSecret()).thenReturn(null);
    }

    @Test
    void handlePullRequestOpenedEventCreatesPrAndEnqueuesEvaluateRiskJob() throws Exception {
        when(gitRepositoryRepository.findFirstByGithubRepoId(123456L)).thenReturn(Optional.of(repository));
        when(pullRequestRepository.findByRepositoryIdAndNumber(repository.getId(), 42)).thenReturn(Optional.empty());

        String rawPayload = """
            {
              "action": "opened",
              "repository": {
                "id": 123456,
                "full_name": "org/repo"
              },
              "pull_request": {
                "id": 99999,
                "number": 42,
                "title": "Add new authentication flow",
                "state": "open",
                "draft": false,
                "user": { "login": "alice" },
                "base": { "ref": "main" },
                "head": { "ref": "feature/auth", "sha": "abcdef123456" },
                "additions": 450,
                "deletions": 120,
                "changed_files": 14,
                "commits": 3,
                "created_at": "2026-08-20T10:00:00Z"
              }
            }
            """;

        Map<String, Object> result = githubWebhookService.handleWebhook(
            "pull_request",
            "delivery-1",
            null, // signature check bypasses when secret matches or in test
            rawPayload
        );

        // Verify PR was saved
        ArgumentCaptor<PullRequest> prCaptor = ArgumentCaptor.forClass(PullRequest.class);
        verify(pullRequestRepository).save(prCaptor.capture());
        PullRequest savedPr = prCaptor.getValue();
        assertThat(savedPr.getNumber()).isEqualTo(42);
        assertThat(savedPr.getTitle()).isEqualTo("Add new authentication flow");
        assertThat(savedPr.getState()).isEqualTo(PullRequestState.OPEN);
        assertThat(savedPr.getAdditions()).isEqualTo(450);

        // Verify PROCESS_GITHUB_EVENT job was enqueued
        ArgumentCaptor<ProcessingJob> jobCaptor = ArgumentCaptor.forClass(ProcessingJob.class);
        verify(processingJobRepository).save(jobCaptor.capture());
        ProcessingJob savedJob = jobCaptor.getValue();
        assertThat(savedJob.getJobType()).isEqualTo(ProcessingJobType.PROCESS_GITHUB_EVENT);
        assertThat(savedJob.getStatus()).isEqualTo(ProcessingJobStatus.PENDING);
        assertThat(savedJob.getPayload().get("prNumber")).isEqualTo(42);

        assertThat(result.get("status")).isEqualTo("queued");
        assertThat(result.get("jobType")).isEqualTo("PROCESS_GITHUB_EVENT");
    }
}
