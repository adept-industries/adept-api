package com.adept.api.risk;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.adept.api.common.domain.MembershipRole;
import com.adept.api.common.domain.ProcessingJobStatus;
import com.adept.api.common.domain.ProcessingJobType;
import com.adept.api.common.domain.RiskLevel;
import com.adept.api.common.error.NotFoundException;
import com.adept.api.common.error.ProblemCode;
import com.adept.api.integration.github.GitRepository;
import com.adept.api.job.ProcessingJob;
import com.adept.api.job.ProcessingJobRepository;
import com.adept.api.project.Project;
import com.adept.api.project.ProjectRepository;
import com.adept.api.project.ProjectRepositoryLinkRepository;
import com.adept.api.pullrequest.PullRequest;
import com.adept.api.risk.dto.ProjectPullRequestRiskItemResponse;
import com.adept.api.risk.dto.ProjectPullRequestRiskPageResponse;
import com.adept.api.risk.dto.ProjectPullRequestRiskRebuildResponse;
import com.adept.api.security.AuthenticatedPrincipal;
import com.adept.api.workspace.WorkspaceAuthorizationService;

@Service
public class ProjectPullRequestRiskService {

    static final Duration STALLED_AFTER = Duration.ofHours(48);
    private static final List<ProcessingJobStatus> ACTIVE_JOB_STATUSES = List.of(
        ProcessingJobStatus.PENDING,
        ProcessingJobStatus.FAILED,
        ProcessingJobStatus.RUNNING
    );

    private final ProjectRepository projectRepository;
    private final ProjectRepositoryLinkRepository linkRepository;
    private final RiskPredictionRepository riskPredictionRepository;
    private final ProcessingJobRepository processingJobRepository;
    private final WorkspaceAuthorizationService workspaceAuthorizationService;
    private final Clock clock;

    public ProjectPullRequestRiskService(
            ProjectRepository projectRepository,
            ProjectRepositoryLinkRepository linkRepository,
            RiskPredictionRepository riskPredictionRepository,
            ProcessingJobRepository processingJobRepository,
            WorkspaceAuthorizationService workspaceAuthorizationService,
            Clock clock) {
        this.projectRepository = projectRepository;
        this.linkRepository = linkRepository;
        this.riskPredictionRepository = riskPredictionRepository;
        this.processingJobRepository = processingJobRepository;
        this.workspaceAuthorizationService = workspaceAuthorizationService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ProjectPullRequestRiskPageResponse list(
            AuthenticatedPrincipal principal,
            UUID projectId,
            int page,
            int size,
            RiskLevel riskLevel,
            boolean stalledOnly) {
        List<GitRepository> repositories = requireReadableRepositories(principal, projectId);
        Instant now = clock.instant();
        Instant stalledBefore = now.minus(STALLED_AFTER);
        if (repositories.isEmpty()) {
            return response(Page.empty(PageRequest.of(page, size)), stalledBefore, page, size);
        }

        Page<RiskPrediction> predictions = riskPredictionRepository.findCurrentOpenByScope(
            principal.workspaceId(),
            repositories.stream().map(GitRepository::getId).toList(),
            PrRiskContract.MODEL_NAME,
            PrRiskContract.MODEL_VERSION,
            PrRiskContract.FEATURE_SCHEMA_VERSION,
            riskLevel == null ? List.of(RiskLevel.values()) : List.of(riskLevel),
            stalledOnly ? stalledBefore : now,
            PageRequest.of(page, size)
        );
        return response(predictions, stalledBefore, page, size);
    }

    @Transactional
    public ProjectPullRequestRiskRebuildResponse rebuild(
            AuthenticatedPrincipal principal,
            UUID projectId) {
        workspaceAuthorizationService.requireManager(principal);
        List<GitRepository> repositories = requireReadableRepositories(principal, projectId);
        int queued = 0;
        int alreadyQueued = 0;
        for (GitRepository repository : repositories) {
            boolean active = processingJobRepository.existsByRepository_IdAndJobTypeAndStatusIn(
                repository.getId(),
                ProcessingJobType.BACKFILL_REPOSITORY,
                ACTIVE_JOB_STATUSES
            );
            if (active) {
                alreadyQueued++;
                continue;
            }
            ProcessingJob job = new ProcessingJob();
            job.setWorkspace(repository.getWorkspace());
            job.setRepository(repository);
            job.setJobType(ProcessingJobType.BACKFILL_REPOSITORY);
            job.setStatus(ProcessingJobStatus.PENDING);
            job.setPriority(40);
            job.setPayload(Map.of(
                "repositoryId", repository.getId().toString(),
                "riskOnly", true,
                "modelVersion", PrRiskContract.MODEL_VERSION
            ));
            job.setAvailableAt(clock.instant());
            processingJobRepository.save(job);
            queued++;
        }
        return new ProjectPullRequestRiskRebuildResponse(
            PrRiskContract.MODEL_VERSION,
            queued,
            alreadyQueued
        );
    }

    private List<GitRepository> requireReadableRepositories(
            AuthenticatedPrincipal principal,
            UUID projectId) {
        Project project = projectRepository.findByIdAndWorkspaceId(projectId, principal.workspaceId())
            .orElseThrow(() -> new NotFoundException(ProblemCode.PROJECT_NOT_FOUND));
        List<GitRepository> repositories = principal.role() == MembershipRole.MANAGER
            ? linkRepository.findAllWithRepositoryByProjectId(project.getId()).stream()
                .map(link -> link.getRepository())
                .filter(GitRepository::isTrackingEnabled)
                .filter(repository -> !repository.isArchived())
                .toList()
            : linkRepository.findAllReadableByLead(project.getId(), principal.membershipId()).stream()
                .map(link -> link.getRepository())
                .toList();
        if (principal.role() != MembershipRole.MANAGER && repositories.isEmpty()) {
            throw new NotFoundException(ProblemCode.PROJECT_NOT_FOUND);
        }
        return repositories;
    }

    private ProjectPullRequestRiskPageResponse response(
            Page<RiskPrediction> predictions,
            Instant stalledBefore,
            int requestedPage,
            int requestedSize) {
        List<ProjectPullRequestRiskItemResponse> items = predictions.getContent().stream()
            .map(prediction -> item(prediction, stalledBefore))
            .toList();
        return new ProjectPullRequestRiskPageResponse(
            PrRiskContract.DISPLAY_LABEL,
            PrRiskContract.DISCLAIMER,
            PrRiskContract.MODEL_NAME,
            PrRiskContract.MODEL_VERSION,
            PrRiskContract.FEATURE_SCHEMA_VERSION,
            stalledBefore,
            items,
            predictions.hasContent() ? predictions.getNumber() : requestedPage,
            predictions.hasContent() ? predictions.getSize() : requestedSize,
            predictions.getTotalElements(),
            predictions.getTotalPages()
        );
    }

    private ProjectPullRequestRiskItemResponse item(
            RiskPrediction prediction,
            Instant stalledBefore) {
        PullRequest pullRequest = prediction.getPullRequest();
        GitRepository repository = prediction.getRepository();
        return new ProjectPullRequestRiskItemResponse(
            pullRequest.getId(),
            repository.getId(),
            repository.getFullName(),
            pullRequest.getNumber(),
            pullRequest.getTitle(),
            pullRequest.isDraft(),
            pullRequest.getAuthorLogin(),
            "https://github.com/" + repository.getFullName() + "/pull/" + pullRequest.getNumber(),
            pullRequest.getOpenedAt(),
            !pullRequest.getOpenedAt().isAfter(stalledBefore),
            prediction.getRiskScore(),
            prediction.getRiskLevel(),
            prediction.getThresholdUsed(),
            List.copyOf(prediction.getTopFactors()),
            prediction.getPredictedAt()
        );
    }
}
