package com.adept.api.integration.github;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.adept.api.audit.AuditAction;
import com.adept.api.audit.AuditService;
import com.adept.api.common.domain.MembershipRole;
import com.adept.api.common.domain.ProcessingJobStatus;
import com.adept.api.common.domain.ProcessingJobType;
import com.adept.api.common.error.ApiException;
import com.adept.api.common.error.ProblemCode;
import com.adept.api.integration.github.dto.LeadCandidateResponse;
import com.adept.api.integration.github.dto.RepositoryResponse;
import com.adept.api.integration.github.dto.RepositorySettingsDto;
import com.adept.api.integration.github.dto.UpdateRepositoryRequest;
import com.adept.api.job.ProcessingJob;
import com.adept.api.job.ProcessingJobRepository;
import com.adept.api.workspace.Membership;
import tools.jackson.databind.ObjectMapper;

@ConditionalOnProperty(name = "app.github.enabled", havingValue = "true")
@Service
public class RepositoryService {

    private final GitRepositoryRepository repositoryRepository;
    private final GithubApiClient githubApiClient;
    private final ProcessingJobRepository processingJobRepository;
    private final AuditService auditService;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public RepositoryService(
            GitRepositoryRepository repositoryRepository,
            GithubApiClient githubApiClient,
            ProcessingJobRepository processingJobRepository,
            AuditService auditService,
            Clock clock,
            ObjectMapper objectMapper) {
        this.repositoryRepository = repositoryRepository;
        this.githubApiClient = githubApiClient;
        this.processingJobRepository = processingJobRepository;
        this.auditService = auditService;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<RepositoryResponse> listRepositories(UUID workspaceId, Membership membership, Boolean trackingOnly) {
        List<GitRepository> repos;
        if (membership.getRole() == MembershipRole.MANAGER) {
            repos = repositoryRepository.findAllByWorkspaceId(workspaceId);
        } else {
            repos = repositoryRepository.findLeadReadableRepositories(
                workspaceId,
                membership.getId(),
                PageRequest.of(0, 1000)
            ).getContent();
        }

        if (Boolean.TRUE.equals(trackingOnly)) {
            repos = repos.stream().filter(GitRepository::isTrackingEnabled).toList();
        }

        return repos.stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public RepositoryResponse getRepository(UUID workspaceId, UUID repositoryId, Membership membership) {
        GitRepository repo = repositoryRepository.findByIdAndWorkspaceId(repositoryId, workspaceId)
            .orElseThrow(() -> new ApiException(ProblemCode.REPOSITORY_NOT_FOUND));

        if (membership != null && membership.getRole() == MembershipRole.LEAD) {
            boolean readable = repositoryRepository.findLeadReadableRepositories(
                workspaceId,
                membership.getId(),
                PageRequest.of(0, 1000)
            ).getContent().stream().anyMatch(r -> r.getId().equals(repositoryId));
            if (!readable) {
                throw new ApiException(ProblemCode.REPOSITORY_NOT_FOUND);
            }
        }

        return toResponse(repo);
    }

    @Transactional
    public RepositoryResponse updateRepository(
            UUID workspaceId,
            UUID repositoryId,
            UpdateRepositoryRequest request,
            Membership membership) {
        verifyManagerRole(membership);

        GitRepository repo = repositoryRepository.findByIdAndWorkspaceId(repositoryId, workspaceId)
            .orElseThrow(() -> new ApiException(ProblemCode.REPOSITORY_NOT_FOUND));

        boolean oldTracking = repo.isTrackingEnabled();

        if (request.trackingEnabled() != null) {
            boolean newTracking = request.trackingEnabled();
            repo.setTrackingEnabled(newTracking);

            if (!oldTracking && newTracking) {
                // Tracking was just enabled -> insert BACKFILL_REPOSITORY job
                int backfillDays = 90;
                RepositorySettingsDto currentSettings = parseSettings(repo.getSettings());
                if (currentSettings != null && currentSettings.backfillDays() != null) {
                    backfillDays = currentSettings.backfillDays();
                }

                ProcessingJob backfillJob = new ProcessingJob();
                backfillJob.setWorkspace(repo.getWorkspace());
                backfillJob.setRepository(repo);
                backfillJob.setJobType(ProcessingJobType.BACKFILL_REPOSITORY);
                backfillJob.setStatus(ProcessingJobStatus.PENDING);
                backfillJob.setPriority(50);
                backfillJob.setPayload(Map.of(
                    "repositoryId", repo.getId().toString(),
                    "backfillDays", backfillDays
                ));
                backfillJob.setAvailableAt(clock.instant());
                processingJobRepository.save(backfillJob);
            }

            auditService.record(
                AuditAction.REPOSITORY_TRACKING_UPDATED,
                membership.getUser(),
                membership,
                repo.getWorkspace(),
                "REPOSITORY",
                repo.getId(),
                Map.of(
                    "repositoryId", repo.getId().toString(),
                    "trackingEnabled", newTracking
                )
            );
        }

        if (request.settings() != null) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> settingsMap = objectMapper.convertValue(request.settings(), Map.class);
                repo.setSettings(settingsMap);
            } catch (Exception exception) {
                throw new ApiException(ProblemCode.VALIDATION_FAILED, "Invalid repository settings format");
            }

            auditService.record(
                AuditAction.REPOSITORY_SETTINGS_UPDATED,
                membership.getUser(),
                membership,
                repo.getWorkspace(),
                "REPOSITORY",
                repo.getId(),
                Map.of(
                    "repositoryId", repo.getId().toString(),
                    "deploymentSignal", request.settings().deploymentSignal()
                )
            );
        }

        repositoryRepository.save(repo);
        return toResponse(repo);
    }

    @Transactional(readOnly = true)
    public List<LeadCandidateResponse> getLeadCandidates(UUID workspaceId, UUID repositoryId, Membership membership) {
        verifyManagerRole(membership);

        GitRepository repo = repositoryRepository.findByIdAndWorkspaceId(repositoryId, workspaceId)
            .orElseThrow(() -> new ApiException(ProblemCode.REPOSITORY_NOT_FOUND));

        long installationId = repo.getGithubIntegration().getInstallationId();
        List<GithubApiClient.GithubLeadCandidate> candidates =
            githubApiClient.listLeadCandidates(installationId, repo.getOwnerLogin(), repo.getName());

        return candidates.stream()
            .map(c -> new LeadCandidateResponse(
                c.githubUserId(),
                c.login(),
                c.avatarUrl(),
                c.permission(),
                c.publicEmail()
            ))
            .toList();
    }

    private void verifyManagerRole(Membership membership) {
        if (membership == null || membership.getRole() != MembershipRole.MANAGER) {
            throw new ApiException(ProblemCode.MANAGER_REQUIRED);
        }
    }

    private RepositoryResponse toResponse(GitRepository repo) {
        return new RepositoryResponse(
            repo.getId(),
            repo.getWorkspace().getId(),
            repo.getGithubIntegration().getId(),
            repo.getGithubRepoId(),
            repo.getOwnerLogin(),
            repo.getName(),
            repo.getFullName(),
            repo.getDefaultBranch(),
            repo.getVisibility(),
            repo.isArchived(),
            repo.isTrackingEnabled(),
            parseSettings(repo.getSettings()),
            repo.getLastSyncedAt()
        );
    }

    private RepositorySettingsDto parseSettings(Map<String, Object> settingsMap) {
        if (settingsMap == null || settingsMap.isEmpty()) {
            return RepositorySettingsDto.defaults();
        }
        try {
            return objectMapper.convertValue(settingsMap, RepositorySettingsDto.class);
        } catch (Exception ignored) {
            return RepositorySettingsDto.defaults();
        }
    }
}
