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
        Map<String, Object> currentEffectiveSettingsMap = mergeSettings(repo.getSettings(), null);
        Map<String, Object> effectiveSettingsMap = mergeSettings(
            repo.getSettings(),
            request.settings()
        );
        RepositorySettingsDto effectiveSettings = parseSettings(effectiveSettingsMap);
        boolean settingsChanged = request.settings() != null
            && !effectiveSettingsMap.equals(currentEffectiveSettingsMap);

        if (request.trackingEnabled() != null) {
            boolean newTracking = request.trackingEnabled();
            if (newTracking && repo.isArchived()) {
                throw new ApiException(
                    ProblemCode.VALIDATION_FAILED,
                    "Archived repositories cannot be tracked"
                );
            }
            repo.setTrackingEnabled(newTracking);

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
            repo.setSettings(effectiveSettingsMap);

            auditService.record(
                AuditAction.REPOSITORY_SETTINGS_UPDATED,
                membership.getUser(),
                membership,
                repo.getWorkspace(),
                "REPOSITORY",
                repo.getId(),
                Map.of(
                    "repositoryId", repo.getId().toString(),
                    "deploymentSignal", effectiveSettings.deploymentSignal()
                )
            );
        }

        boolean trackingEnabled = request.trackingEnabled() != null
            ? request.trackingEnabled()
            : oldTracking;
        if (trackingEnabled && !repo.isArchived() && ((!oldTracking) || settingsChanged)) {
            enqueueBackfill(repo, effectiveSettings.backfillDays());
        }

        repositoryRepository.save(repo);
        return toResponse(repo);
    }

    @Transactional
    public void requestBackfill(
            UUID workspaceId,
            UUID repositoryId,
            Membership membership) {
        verifyManagerRole(membership);
        GitRepository repo = repositoryRepository.findByIdAndWorkspaceId(repositoryId, workspaceId)
            .orElseThrow(() -> new ApiException(ProblemCode.REPOSITORY_NOT_FOUND));
        if (repo.isArchived() || !repo.isTrackingEnabled()) {
            throw new ApiException(
                ProblemCode.VALIDATION_FAILED,
                "Only tracked, non-archived repositories can be rebuilt"
            );
        }
        enqueueBackfill(repo, parseSettings(repo.getSettings()).backfillDays());
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

    private void enqueueBackfill(GitRepository repo, Integer configuredBackfillDays) {
        boolean alreadyQueued = processingJobRepository.existsByRepository_IdAndJobTypeAndStatusIn(
            repo.getId(),
            ProcessingJobType.BACKFILL_REPOSITORY,
            List.of(
                ProcessingJobStatus.PENDING,
                ProcessingJobStatus.FAILED,
                ProcessingJobStatus.RUNNING
            )
        );
        if (alreadyQueued) {
            return;
        }
        int backfillDays = configuredBackfillDays != null ? configuredBackfillDays : 90;
        ProcessingJob backfillJob = new ProcessingJob();
        backfillJob.setWorkspace(repo.getWorkspace());
        backfillJob.setRepository(repo);
        backfillJob.setJobType(ProcessingJobType.BACKFILL_REPOSITORY);
        backfillJob.setStatus(ProcessingJobStatus.PENDING);
        backfillJob.setPriority(50);
        backfillJob.setPayload(Map.of(
            "repositoryId", repo.getId().toString(),
            "backfillDays", backfillDays,
            "rebuildDerivedData", true
        ));
        backfillJob.setAvailableAt(clock.instant());
        processingJobRepository.save(backfillJob);
    }

    private Map<String, Object> mergeSettings(
            Map<String, Object> currentSettings,
            RepositorySettingsDto patch) {
        Map<String, Object> merged = settingsMap(RepositorySettingsDto.defaults());
        if (currentSettings != null) {
            merged.putAll(currentSettings);
        }
        if (patch != null) {
            Map<String, Object> patchMap = settingsMap(patch);
            patchMap.forEach((key, value) -> {
                if (value != null) {
                    merged.put(key, value);
                }
            });
        }
        return merged;
    }

    private Map<String, Object> settingsMap(RepositorySettingsDto settings) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> converted = objectMapper.convertValue(settings, Map.class);
            return new HashMap<>(converted);
        } catch (Exception exception) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Invalid repository settings format");
        }
    }

    public RepositoryResponse toResponse(GitRepository repo) {
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
        try {
            Map<String, Object> complete = settingsMap(RepositorySettingsDto.defaults());
            if (settingsMap != null) {
                settingsMap.forEach((key, value) -> {
                    if (value != null) {
                        complete.put(key, value);
                    }
                });
            }
            return objectMapper.convertValue(complete, RepositorySettingsDto.class);
        } catch (Exception ignored) {
            return RepositorySettingsDto.defaults();
        }
    }
}
