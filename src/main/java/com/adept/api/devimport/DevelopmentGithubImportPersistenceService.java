package com.adept.api.devimport;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.adept.api.common.domain.DeploymentSource;
import com.adept.api.common.domain.DeploymentStatus;
import com.adept.api.common.domain.GithubAccountType;
import com.adept.api.common.domain.IntegrationStatus;
import com.adept.api.common.domain.MembershipRole;
import com.adept.api.common.domain.MembershipStatus;
import com.adept.api.common.domain.ProcessingJobStatus;
import com.adept.api.common.domain.ProcessingJobType;
import com.adept.api.common.domain.PullRequestState;
import com.adept.api.common.domain.RepositorySelection;
import com.adept.api.common.domain.RepositoryVisibility;
import com.adept.api.common.domain.UserStatus;
import com.adept.api.common.domain.WorkspaceStatus;
import com.adept.api.crypto.PasswordService;
import com.adept.api.deployment.Deployment;
import com.adept.api.deployment.DeploymentRepository;
import com.adept.api.integration.github.GitRepository;
import com.adept.api.integration.github.GitRepositoryRepository;
import com.adept.api.integration.github.GithubIntegration;
import com.adept.api.integration.github.GithubIntegrationRepository;
import com.adept.api.integration.github.RepositoryLeadAssignment;
import com.adept.api.integration.github.RepositoryLeadAssignmentRepository;
import com.adept.api.integration.github.dto.RepositorySettingsDto;
import com.adept.api.job.ProcessingJob;
import com.adept.api.job.ProcessingJobRepository;
import com.adept.api.project.Project;
import com.adept.api.project.ProjectRepository;
import com.adept.api.project.ProjectRepositoryLink;
import com.adept.api.project.ProjectRepositoryLinkId;
import com.adept.api.project.ProjectRepositoryLinkRepository;
import com.adept.api.pullrequest.PullRequest;
import com.adept.api.pullrequest.PullRequestFeature;
import com.adept.api.pullrequest.PullRequestFeatureRepository;
import com.adept.api.pullrequest.PullRequestRepository;
import com.adept.api.user.User;
import com.adept.api.user.UserRepository;
import com.adept.api.workspace.Membership;
import com.adept.api.workspace.MembershipRepository;
import com.adept.api.workspace.Workspace;
import com.adept.api.workspace.WorkspaceRepository;

import tools.jackson.databind.ObjectMapper;

@Profile("local")
@Service
class DevelopmentGithubImportPersistenceService {

    private static final String FEATURE_SCHEMA_VERSION = "github-history-v1";

    private final WorkspaceRepository workspaceRepository;
    private final UserRepository userRepository;
    private final MembershipRepository membershipRepository;
    private final GithubIntegrationRepository githubIntegrationRepository;
    private final GitRepositoryRepository gitRepositoryRepository;
    private final RepositoryLeadAssignmentRepository leadAssignmentRepository;
    private final ProjectRepository projectRepository;
    private final ProjectRepositoryLinkRepository projectRepositoryLinkRepository;
    private final PullRequestRepository pullRequestRepository;
    private final PullRequestFeatureRepository pullRequestFeatureRepository;
    private final DeploymentRepository deploymentRepository;
    private final ProcessingJobRepository processingJobRepository;
    private final PasswordService passwordService;
    private final ObjectMapper objectMapper;

    DevelopmentGithubImportPersistenceService(
            WorkspaceRepository workspaceRepository,
            UserRepository userRepository,
            MembershipRepository membershipRepository,
            GithubIntegrationRepository githubIntegrationRepository,
            GitRepositoryRepository gitRepositoryRepository,
            RepositoryLeadAssignmentRepository leadAssignmentRepository,
            ProjectRepository projectRepository,
            ProjectRepositoryLinkRepository projectRepositoryLinkRepository,
            PullRequestRepository pullRequestRepository,
            PullRequestFeatureRepository pullRequestFeatureRepository,
            DeploymentRepository deploymentRepository,
            ProcessingJobRepository processingJobRepository,
            PasswordService passwordService,
            ObjectMapper objectMapper) {
        this.workspaceRepository = workspaceRepository;
        this.userRepository = userRepository;
        this.membershipRepository = membershipRepository;
        this.githubIntegrationRepository = githubIntegrationRepository;
        this.gitRepositoryRepository = gitRepositoryRepository;
        this.leadAssignmentRepository = leadAssignmentRepository;
        this.projectRepository = projectRepository;
        this.projectRepositoryLinkRepository = projectRepositoryLinkRepository;
        this.pullRequestRepository = pullRequestRepository;
        this.pullRequestFeatureRepository = pullRequestFeatureRepository;
        this.deploymentRepository = deploymentRepository;
        this.processingJobRepository = processingJobRepository;
        this.passwordService = passwordService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    DevelopmentGithubImportResult persist(
            DevelopmentGithubImportOptions options,
            DevelopmentGithubImportPayload payload) {
        Instant now = Instant.now();
        ImportStats stats = new ImportStats();
        Workspace workspace = findOrCreateWorkspace(options);
        String passwordHash = passwordService.encodeNewPassword(options.demoPassword());
        User manager = upsertDemoUser(options.managerEmail(), "Demo Engineering Manager", passwordHash, now);
        User lead = upsertDemoUser(options.leadEmail(), "Demo Repository Lead", passwordHash, now);
        User coLead = upsertDemoUser(options.coLeadEmail(), "Demo Co-Lead", passwordHash, now);
        Membership managerMembership = upsertMembership(workspace, manager, MembershipRole.MANAGER);
        Membership leadMembership = upsertMembership(workspace, lead, MembershipRole.LEAD);
        Membership coLeadMembership = upsertMembership(workspace, coLead, MembershipRole.LEAD);
        GithubIntegration integration = upsertDevelopmentIntegration(workspace, managerMembership, payload, options, now);
        GitRepository repository = upsertRepository(workspace, integration, payload.repository(), now);
        Project project = upsertProject(workspace, managerMembership, options.projectName());
        linkProjectRepository(workspace, project, repository);
        assignLead(repository, leadMembership, managerMembership, now);
        assignLead(repository, coLeadMembership, managerMembership, now);

        List<DevelopmentGithubPullRequestImport> pullRequests = payload.pullRequests().stream()
            .sorted(Comparator
                .comparing((DevelopmentGithubPullRequestImport pr) -> instantValue(pr.pullRequest(), "created_at", Instant.EPOCH))
                .thenComparing(pr -> intValue(pr.pullRequest().get("number"))))
            .toList();
        Map<String, AuthorHistory> authorHistory = new HashMap<>();
        for (DevelopmentGithubPullRequestImport pullRequestImport : pullRequests) {
            PullRequest pullRequest = upsertPullRequest(
                workspace,
                repository,
                pullRequestImport,
                payload.repository(),
                now,
                stats
            );
            upsertFeature(workspace, repository, pullRequest, pullRequestImport, authorHistory, now, stats);
        }

        for (Map<String, Object> workflowRun : payload.workflowRuns()) {
            upsertWorkflowDeployment(workspace, repository, workflowRun, stats);
        }

        boolean queuedRecalculation = queueRecalculateMetricsIfNeeded(workspace, repository);

        return new DevelopmentGithubImportResult(
            workspace.getName(),
            workspace.getSlug(),
            project.getName(),
            repository.getFullName(),
            options.managerEmail(),
            options.leadEmail(),
            options.coLeadEmail(),
            payload.contributors().size(),
            stats.pullRequestsCreated,
            stats.pullRequestsUpdated,
            stats.featuresCreated,
            stats.featuresUpdated,
            stats.workflowDeploymentsCreated,
            stats.workflowDeploymentsUpdated,
            queuedRecalculation,
            false
        );
    }

    @Transactional
    DevelopmentGithubImportResult removeDemoData(DevelopmentGithubImportOptions options) {
        workspaceRepository.findBySlug(options.workspaceSlug()).ifPresent(workspace -> {
            if (!workspace.getName().equals(options.workspaceName())) {
                throw new IllegalStateException(
                    "Refusing to remove workspace slug " + options.workspaceSlug()
                        + " because its name is not " + options.workspaceName() + "."
                );
            }
            workspaceRepository.delete(workspace);
            workspaceRepository.flush();
        });

        deleteDemoUserIfUnowned(options.managerEmail());
        deleteDemoUserIfUnowned(options.leadEmail());
        deleteDemoUserIfUnowned(options.coLeadEmail());

        return new DevelopmentGithubImportResult(
            options.workspaceName(),
            options.workspaceSlug(),
            options.projectName(),
            options.repository(),
            options.managerEmail(),
            options.leadEmail(),
            options.coLeadEmail(),
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            false,
            true
        );
    }

    private Workspace findOrCreateWorkspace(DevelopmentGithubImportOptions options) {
        return workspaceRepository.findBySlug(options.workspaceSlug())
            .map(workspace -> {
                if (!workspace.getName().equals(options.workspaceName())) {
                    throw new IllegalStateException(
                        "Workspace slug " + options.workspaceSlug()
                            + " already exists with another name. Choose another ADEPT_DEMO_WORKSPACE_SLUG."
                    );
                }
                workspace.setStatus(WorkspaceStatus.ACTIVE);
                return workspaceRepository.save(workspace);
            })
            .orElseGet(() -> {
                Workspace workspace = new Workspace();
                workspace.setName(options.workspaceName());
                workspace.setSlug(options.workspaceSlug());
                workspace.setTimezone("UTC");
                workspace.setStatus(WorkspaceStatus.ACTIVE);
                return workspaceRepository.saveAndFlush(workspace);
            });
    }

    private User upsertDemoUser(String email, String displayName, String passwordHash, Instant now) {
        User user = userRepository.findByEmailIgnoreCase(email).orElseGet(User::new);
        user.setEmail(email);
        user.setDisplayName(displayName);
        user.setPasswordHash(passwordHash);
        user.setStatus(UserStatus.ACTIVE);
        user.setEmailVerifiedAt(now);
        return userRepository.saveAndFlush(user);
    }

    private Membership upsertMembership(Workspace workspace, User user, MembershipRole role) {
        Membership membership = membershipRepository
            .findByWorkspaceIdAndUserId(workspace.getId(), user.getId())
            .orElseGet(Membership::new);
        membership.setWorkspace(workspace);
        membership.setUser(user);
        membership.setRole(role);
        membership.setStatus(MembershipStatus.ACTIVE);
        if (membership.getJoinedAt() == null) {
            membership.setJoinedAt(Instant.now());
        }
        return membershipRepository.saveAndFlush(membership);
    }

    private GithubIntegration upsertDevelopmentIntegration(
            Workspace workspace,
            Membership managerMembership,
            DevelopmentGithubImportPayload payload,
            DevelopmentGithubImportOptions options,
            Instant now) {
        Map<String, Object> repository = payload.repository();
        Map<String, Object> owner = mapValue(repository, "owner");
        String ownerLogin = stringValue(owner.get("login"), options.owner());
        long pseudoInstallationId = negativeStableId("adept-dev-import:" + workspace.getId() + ":" + ownerLogin);
        GithubIntegration integration = githubIntegrationRepository.findByInstallationId(pseudoInstallationId)
            .orElseGet(GithubIntegration::new);
        if (integration.getId() != null && !integration.getWorkspace().getId().equals(workspace.getId())) {
            throw new IllegalStateException("A development GitHub integration ID collision occurred.");
        }

        integration.setWorkspace(workspace);
        integration.setInstallationId(pseudoInstallationId);
        integration.setAccountExternalId(longValue(owner.get("id"), Math.abs(pseudoInstallationId)));
        integration.setAccountLogin(ownerLogin);
        integration.setAccountType("Organization".equalsIgnoreCase(stringValue(owner.get("type"), "User"))
            ? GithubAccountType.ORGANIZATION
            : GithubAccountType.USER);
        integration.setRepositorySelection(RepositorySelection.ALL);
        integration.setStatus(IntegrationStatus.ACTIVE);
        integration.setPermissions(developmentImportPermissions(options, payload.contributors()));
        integration.setInstalledBy(managerMembership);
        integration.setInstalledAt(now);
        integration.setLastSyncedAt(now);
        return githubIntegrationRepository.saveAndFlush(integration);
    }

    private GitRepository upsertRepository(
            Workspace workspace,
            GithubIntegration integration,
            Map<String, Object> data,
            Instant now) {
        long githubRepoId = longValue(data.get("id"), 0);
        if (githubRepoId == 0) {
            throw new IllegalStateException("GitHub repository response did not include an id.");
        }

        GitRepository repository = gitRepositoryRepository
            .findByWorkspaceIdAndGithubRepoId(workspace.getId(), githubRepoId)
            .orElseGet(GitRepository::new);
        repository.setWorkspace(workspace);
        repository.setGithubIntegration(integration);
        repository.setGithubRepoId(githubRepoId);
        repository.setGithubNodeId(nullableString(data.get("node_id")));
        repository.setOwnerLogin(stringValue(mapValue(data, "owner").get("login"), stringValue(data.get("owner_login"), "")));
        repository.setName(stringValue(data.get("name"), ""));
        repository.setFullName(stringValue(data.get("full_name"), ""));
        repository.setDefaultBranch(stringValue(data.get("default_branch"), "main"));
        repository.setVisibility(Boolean.TRUE.equals(data.get("private"))
            ? RepositoryVisibility.PRIVATE
            : RepositoryVisibility.PUBLIC);
        repository.setArchived(booleanValue(data.get("archived")));
        repository.setTrackingEnabled(true);
        repository.setSettings(defaultRepositorySettings());
        repository.setLastSyncedAt(now);
        return gitRepositoryRepository.saveAndFlush(repository);
    }

    private Project upsertProject(Workspace workspace, Membership managerMembership, String projectName) {
        Project project = projectRepository
            .findByWorkspaceIdAndNameIgnoreCase(workspace.getId(), projectName)
            .orElseGet(Project::new);
        project.setWorkspace(workspace);
        project.setName(projectName);
        project.setDescription("Development imports from real public GitHub repository history.");
        project.setCreatedByMembership(managerMembership);
        return projectRepository.saveAndFlush(project);
    }

    private void linkProjectRepository(Workspace workspace, Project project, GitRepository repository) {
        ProjectRepositoryLinkId id = new ProjectRepositoryLinkId(project.getId(), repository.getId());
        if (projectRepositoryLinkRepository.existsById(id)) {
            return;
        }
        ProjectRepositoryLink link = new ProjectRepositoryLink();
        link.setId(id);
        link.setWorkspace(workspace);
        link.setProject(project);
        link.setRepository(repository);
        link.setCreatedAt(Instant.now());
        projectRepositoryLinkRepository.save(link);
    }

    private void assignLead(
            GitRepository repository,
            Membership leadMembership,
            Membership managerMembership,
            Instant now) {
        if (leadAssignmentRepository
                .existsByRepositoryIdAndLeadMembershipId(repository.getId(), leadMembership.getId())) {
            return;
        }
        RepositoryLeadAssignment assignment = new RepositoryLeadAssignment();
        assignment.setWorkspace(repository.getWorkspace());
        assignment.setRepository(repository);
        assignment.setLeadMembership(leadMembership);
        assignment.setAssignedBy(managerMembership);
        assignment.setAssignedAt(now);
        leadAssignmentRepository.save(assignment);
    }

    private PullRequest upsertPullRequest(
            Workspace workspace,
            GitRepository repository,
            DevelopmentGithubPullRequestImport data,
            Map<String, Object> repositoryData,
            Instant now,
            ImportStats stats) {
        Map<String, Object> pullRequestData = data.pullRequest();
        long githubPrId = longValue(pullRequestData.get("id"), 0);
        PullRequest pullRequest = pullRequestRepository
            .findByRepositoryIdAndGithubPrId(repository.getId(), githubPrId)
            .orElseGet(() -> {
                stats.pullRequestsCreated++;
                return new PullRequest();
            });
        if (pullRequest.getId() != null) {
            stats.pullRequestsUpdated++;
        }

        Instant mergedAt = instantValue(pullRequestData, "merged_at", null);
        Instant closedAt = instantValue(pullRequestData, "closed_at", null);
        PullRequestState state = mergedAt != null
            ? PullRequestState.MERGED
            : "closed".equalsIgnoreCase(nullableString(pullRequestData.get("state")))
                ? PullRequestState.CLOSED
                : PullRequestState.OPEN;

        pullRequest.setWorkspace(workspace);
        pullRequest.setRepository(repository);
        pullRequest.setGithubPrId(githubPrId);
        pullRequest.setGithubNodeId(nullableString(pullRequestData.get("node_id")));
        pullRequest.setNumber(intValue(pullRequestData.get("number")));
        pullRequest.setTitle(stringValue(pullRequestData.get("title"), "(untitled pull request)"));
        pullRequest.setState(state);
        pullRequest.setDraft(booleanValue(pullRequestData.get("draft")));
        pullRequest.setAuthorLogin(nullableString(mapValue(pullRequestData, "user").get("login")));
        pullRequest.setBaseRef(stringValue(mapValue(pullRequestData, "base").get("ref"), repository.getDefaultBranch()));
        pullRequest.setHeadRef(stringValue(mapValue(pullRequestData, "head").get("ref"), ""));
        pullRequest.setHeadSha(nullableString(mapValue(pullRequestData, "head").get("sha")));
        pullRequest.setMergeCommitSha(nullableString(pullRequestData.get("merge_commit_sha")));
        pullRequest.setAdditions(intValue(pullRequestData.get("additions")));
        pullRequest.setDeletions(intValue(pullRequestData.get("deletions")));
        pullRequest.setChangedFiles(intValue(pullRequestData.get("changed_files")));
        pullRequest.setCommitCount(intValue(pullRequestData.get("commits")));
        pullRequest.setOpenedAt(instantValue(pullRequestData, "created_at", now));
        pullRequest.setFirstCommitAt(firstCommitAt(data.commits()));
        pullRequest.setClosedAt(closedAt);
        pullRequest.setMergedAt(mergedAt);
        pullRequest.setLastSyncedAt(now);
        pullRequest.setRawData(rawPullRequestData(repositoryData, data, now));
        return pullRequestRepository.saveAndFlush(pullRequest);
    }

    private void upsertFeature(
            Workspace workspace,
            GitRepository repository,
            PullRequest pullRequest,
            DevelopmentGithubPullRequestImport data,
            Map<String, AuthorHistory> authorHistory,
            Instant now,
            ImportStats stats) {
        String authorLogin = pullRequest.getAuthorLogin() == null
            ? "unknown"
            : pullRequest.getAuthorLogin().toLowerCase(Locale.ROOT);
        AuthorHistory history = authorHistory.computeIfAbsent(authorLogin, ignored -> new AuthorHistory());
        PullRequestFeature feature = pullRequestFeatureRepository
            .findByPullRequestIdAndFeatureSchemaVersion(pullRequest.getId(), FEATURE_SCHEMA_VERSION)
            .orElseGet(() -> {
                stats.featuresCreated++;
                return new PullRequestFeature();
            });
        if (feature.getId() != null) {
            stats.featuresUpdated++;
        }

        feature.setWorkspace(workspace);
        feature.setRepository(repository);
        feature.setPullRequest(pullRequest);
        feature.setFeatureSchemaVersion(FEATURE_SCHEMA_VERSION);
        feature.setLinesAdded(pullRequest.getAdditions());
        feature.setLinesDeleted(pullRequest.getDeletions());
        feature.setFilesChanged(pullRequest.getChangedFiles());
        feature.setCommitCount(pullRequest.getCommitCount());
        feature.setAuthorPriorPrCount(history.totalPullRequests);
        feature.setAuthorPriorMergeRate(history.totalPullRequests == 0
            ? null
            : (double) history.mergedPullRequests / history.totalPullRequests);
        feature.setTestFileRatio(testFileRatio(data.files()));
        feature.setEntropy(changeEntropy(data.files()));
        feature.setFeaturePayload(featurePayload(data, history));
        feature.setExtractedAt(now);
        pullRequestFeatureRepository.save(feature);

        history.totalPullRequests++;
        if (pullRequest.getState() == PullRequestState.MERGED) {
            history.mergedPullRequests++;
        }
    }

    private void upsertWorkflowDeployment(
            Workspace workspace,
            GitRepository repository,
            Map<String, Object> workflowRun,
            ImportStats stats) {
        String externalId = stringValue(workflowRun.get("id"), "");
        String commitSha = stringValue(workflowRun.get("head_sha"), "");
        if (externalId.isBlank() || commitSha.isBlank()) {
            return;
        }
        Deployment deployment = deploymentRepository
            .findByRepositoryIdAndSourceAndExternalDeploymentId(
                repository.getId(),
                DeploymentSource.GITHUB_WORKFLOW,
                externalId
            )
            .orElseGet(() -> {
                stats.workflowDeploymentsCreated++;
                return new Deployment();
            });
        if (deployment.getId() != null) {
            stats.workflowDeploymentsUpdated++;
        }

        deployment.setWorkspace(workspace);
        deployment.setRepository(repository);
        deployment.setSource(DeploymentSource.GITHUB_WORKFLOW);
        deployment.setExternalDeploymentId(externalId);
        deployment.setEnvironment("github-actions");
        deployment.setProduction(false);
        deployment.setStatus(workflowStatus(workflowRun));
        deployment.setCommitSha(commitSha);
        deployment.setStartedAt(instantValue(workflowRun, "run_started_at", instantValue(workflowRun, "created_at", null)));
        deployment.setFinishedAt("completed".equalsIgnoreCase(nullableString(workflowRun.get("status")))
            ? instantValue(workflowRun, "updated_at", null)
            : null);
        deployment.setRawData(compactWorkflowRun(workflowRun));
        deploymentRepository.save(deployment);
    }

    private boolean queueRecalculateMetricsIfNeeded(Workspace workspace, GitRepository repository) {
        boolean existing = processingJobRepository.existsByRepositoryIdAndJobTypeAndStatusIn(
            repository.getId(),
            ProcessingJobType.RECALCULATE_METRICS,
            List.of(ProcessingJobStatus.PENDING, ProcessingJobStatus.RUNNING, ProcessingJobStatus.FAILED)
        );
        if (existing) {
            return false;
        }
        ProcessingJob job = new ProcessingJob();
        job.setWorkspace(workspace);
        job.setRepository(repository);
        job.setJobType(ProcessingJobType.RECALCULATE_METRICS);
        job.setStatus(ProcessingJobStatus.PENDING);
        job.setPriority(75);
        job.setPayload(Map.of(
            "repositoryId", repository.getId().toString(),
            "source", "github-historical-import",
            "featureSchemaVersion", FEATURE_SCHEMA_VERSION
        ));
        job.setAvailableAt(Instant.now());
        processingJobRepository.save(job);
        return true;
    }

    private void deleteDemoUserIfUnowned(String email) {
        userRepository.findByEmailIgnoreCase(email).ifPresent(user -> {
            if (membershipRepository.findAllByUserId(user.getId()).isEmpty()) {
                userRepository.delete(user);
            }
        });
    }

    private Map<String, Object> rawPullRequestData(
            Map<String, Object> repository,
            DevelopmentGithubPullRequestImport data,
            Instant importedAt) {
        Map<String, Object> rawData = new LinkedHashMap<>();
        rawData.put("source", "github-historical-import");
        rawData.put("importedAt", importedAt.toString());
        rawData.put("repository", compactRepository(repository));
        rawData.put("pullRequest", compactPullRequest(data.pullRequest()));
        rawData.put("commits", compactCommits(data.commits()));
        rawData.put("reviews", compactReviews(data.reviews()));
        rawData.put("comments", compactComments(data.comments()));
        rawData.put("files", compactFiles(data.files()));
        return rawData;
    }

    private Map<String, Object> featurePayload(
            DevelopmentGithubPullRequestImport data,
            AuthorHistory history) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", "github-historical-import");
        payload.put("commitSample", compactCommits(data.commits()).stream().limit(20).toList());
        payload.put("reviewCount", data.reviews().size());
        payload.put("commentCount", data.comments().size());
        payload.put("files", compactFiles(data.files()));
        payload.put("authorPriorPullRequests", history.totalPullRequests);
        payload.put("authorPriorMergedPullRequests", history.mergedPullRequests);
        return payload;
    }

    private List<Map<String, Object>> compactContributors(List<Map<String, Object>> contributors) {
        return contributors.stream()
            .map(contributor -> compactMap(
                "id", contributor.get("id"),
                "login", contributor.get("login"),
                "avatarUrl", contributor.get("avatar_url"),
                "contributions", contributor.get("contributions"),
                "htmlUrl", contributor.get("html_url")
            ))
            .toList();
    }

    private Map<String, Object> compactRepository(Map<String, Object> repository) {
        Map<String, Object> owner = mapValue(repository, "owner");
        return compactMap(
            "id", repository.get("id"),
            "nodeId", repository.get("node_id"),
            "fullName", repository.get("full_name"),
            "defaultBranch", repository.get("default_branch"),
            "private", repository.get("private"),
            "archived", repository.get("archived"),
            "htmlUrl", repository.get("html_url"),
            "owner", compactMap(
                "id", owner.get("id"),
                "login", owner.get("login"),
                "type", owner.get("type"),
                "avatarUrl", owner.get("avatar_url")
            )
        );
    }

    private Map<String, Object> compactPullRequest(Map<String, Object> pullRequest) {
        return compactMap(
            "id", pullRequest.get("id"),
            "nodeId", pullRequest.get("node_id"),
            "number", pullRequest.get("number"),
            "state", pullRequest.get("state"),
            "title", pullRequest.get("title"),
            "body", truncate(nullableString(pullRequest.get("body")), 2000),
            "draft", pullRequest.get("draft"),
            "user", compactUser(mapValue(pullRequest, "user")),
            "htmlUrl", pullRequest.get("html_url"),
            "createdAt", pullRequest.get("created_at"),
            "updatedAt", pullRequest.get("updated_at"),
            "closedAt", pullRequest.get("closed_at"),
            "mergedAt", pullRequest.get("merged_at"),
            "mergeCommitSha", pullRequest.get("merge_commit_sha"),
            "base", compactBranch(mapValue(pullRequest, "base")),
            "head", compactBranch(mapValue(pullRequest, "head")),
            "additions", pullRequest.get("additions"),
            "deletions", pullRequest.get("deletions"),
            "changedFiles", pullRequest.get("changed_files"),
            "commits", pullRequest.get("commits"),
            "comments", pullRequest.get("comments"),
            "reviewComments", pullRequest.get("review_comments")
        );
    }

    private List<Map<String, Object>> compactCommits(List<Map<String, Object>> commits) {
        return commits.stream()
            .map(commit -> {
                Map<String, Object> commitDetails = mapValue(commit, "commit");
                return compactMap(
                    "sha", commit.get("sha"),
                    "authorLogin", mapValue(commit, "author").get("login"),
                    "committerLogin", mapValue(commit, "committer").get("login"),
                    "authorDate", mapValue(commitDetails, "author").get("date"),
                    "committerDate", mapValue(commitDetails, "committer").get("date"),
                    "message", truncate(nullableString(commitDetails.get("message")), 500),
                    "htmlUrl", commit.get("html_url")
                );
            })
            .toList();
    }

    private List<Map<String, Object>> compactReviews(List<Map<String, Object>> reviews) {
        return reviews.stream()
            .map(review -> compactMap(
                "id", review.get("id"),
                "user", compactUser(mapValue(review, "user")),
                "state", review.get("state"),
                "submittedAt", review.get("submitted_at"),
                "commitId", review.get("commit_id"),
                "htmlUrl", review.get("html_url")
            ))
            .toList();
    }

    private List<Map<String, Object>> compactComments(List<Map<String, Object>> comments) {
        return comments.stream()
            .map(comment -> compactMap(
                "id", comment.get("id"),
                "user", compactUser(mapValue(comment, "user")),
                "createdAt", comment.get("created_at"),
                "updatedAt", comment.get("updated_at"),
                "body", truncate(nullableString(comment.get("body")), 1000),
                "htmlUrl", comment.get("html_url")
            ))
            .toList();
    }

    private List<Map<String, Object>> compactFiles(List<Map<String, Object>> files) {
        return files.stream()
            .map(file -> compactMap(
                "sha", file.get("sha"),
                "filename", file.get("filename"),
                "status", file.get("status"),
                "additions", file.get("additions"),
                "deletions", file.get("deletions"),
                "changes", file.get("changes"),
                "previousFilename", file.get("previous_filename"),
                "blobUrl", file.get("blob_url")
            ))
            .toList();
    }

    private Map<String, Object> compactWorkflowRun(Map<String, Object> workflowRun) {
        return compactMap(
            "source", "github-historical-import",
            "id", workflowRun.get("id"),
            "name", workflowRun.get("name"),
            "event", workflowRun.get("event"),
            "status", workflowRun.get("status"),
            "conclusion", workflowRun.get("conclusion"),
            "workflowId", workflowRun.get("workflow_id"),
            "runNumber", workflowRun.get("run_number"),
            "headBranch", workflowRun.get("head_branch"),
            "headSha", workflowRun.get("head_sha"),
            "runStartedAt", workflowRun.get("run_started_at"),
            "createdAt", workflowRun.get("created_at"),
            "updatedAt", workflowRun.get("updated_at"),
            "htmlUrl", workflowRun.get("html_url")
        );
    }

    private Map<String, Object> compactBranch(Map<String, Object> branch) {
        return compactMap(
            "ref", branch.get("ref"),
            "sha", branch.get("sha"),
            "label", branch.get("label")
        );
    }

    private Map<String, Object> compactUser(Map<String, Object> user) {
        return compactMap(
            "id", user.get("id"),
            "login", user.get("login"),
            "avatarUrl", user.get("avatar_url"),
            "htmlUrl", user.get("html_url")
        );
    }

    private Map<String, Object> developmentImportPermissions(
            DevelopmentGithubImportOptions options,
            List<Map<String, Object>> contributors) {
        Map<String, Object> permissions = new LinkedHashMap<>();
        permissions.put("metadata", "read");
        permissions.put("contents", "read");
        permissions.put("pull_requests", "read");
        permissions.put("actions", "read");
        permissions.put("developmentImport", Map.of(
            "source", "public-github-api",
            "repository", options.repository(),
            "contributors", compactContributors(contributors)
        ));
        return permissions;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> defaultRepositorySettings() {
        return objectMapper.convertValue(RepositorySettingsDto.defaults(), Map.class);
    }

    private static Instant firstCommitAt(List<Map<String, Object>> commits) {
        return commits.stream()
            .map(commit -> {
                Map<String, Object> commitDetails = mapValue(commit, "commit");
                Instant authorDate = instantValue(mapValue(commitDetails, "author"), "date", null);
                if (authorDate != null) {
                    return authorDate;
                }
                return instantValue(mapValue(commitDetails, "committer"), "date", null);
            })
            .filter(value -> value != null)
            .min(Comparator.naturalOrder())
            .orElse(null);
    }

    private static Double testFileRatio(List<Map<String, Object>> files) {
        if (files.isEmpty()) {
            return null;
        }
        long testFiles = files.stream()
            .map(file -> stringValue(file.get("filename"), "").toLowerCase(Locale.ROOT))
            .filter(filename -> filename.contains("test") || filename.contains("spec"))
            .count();
        return (double) testFiles / files.size();
    }

    private static Double changeEntropy(List<Map<String, Object>> files) {
        List<Integer> changes = files.stream()
            .map(file -> intValue(file.get("changes")))
            .filter(value -> value > 0)
            .toList();
        int total = changes.stream().mapToInt(Integer::intValue).sum();
        if (total == 0 || changes.size() <= 1) {
            return null;
        }
        double entropy = 0.0;
        for (int fileChanges : changes) {
            double probability = (double) fileChanges / total;
            entropy -= probability * Math.log(probability);
        }
        return entropy / Math.log(changes.size());
    }

    private static DeploymentStatus workflowStatus(Map<String, Object> workflowRun) {
        String status = nullableString(workflowRun.get("status"));
        if (!"completed".equalsIgnoreCase(status)) {
            if ("queued".equalsIgnoreCase(status) || "requested".equalsIgnoreCase(status)) {
                return DeploymentStatus.QUEUED;
            }
            return DeploymentStatus.IN_PROGRESS;
        }

        return switch (stringValue(workflowRun.get("conclusion"), "").toLowerCase(Locale.ROOT)) {
            case "success" -> DeploymentStatus.SUCCESS;
            case "cancelled", "skipped", "timed_out" -> DeploymentStatus.CANCELLED;
            default -> DeploymentStatus.FAILURE;
        };
    }

    private static Map<String, Object> compactMap(Object... keyValues) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            Object value = keyValues[i + 1];
            if (value != null) {
                result.put((String) keyValues[i], value);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapValue(Map<String, Object> source, String key) {
        Object value = source.get(key);
        if (value instanceof Map<?, ?>) {
            return (Map<String, Object>) value;
        }
        return Map.of();
    }

    private static String stringValue(Object value, String defaultValue) {
        String text = nullableString(value);
        return text == null || text.isBlank() ? defaultValue : text;
    }

    private static String nullableString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return 0;
        }
        return Integer.parseInt(String.valueOf(value));
    }

    private static long longValue(Object value, long defaultValue) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return defaultValue;
        }
        return Long.parseLong(String.valueOf(value));
    }

    private static boolean booleanValue(Object value) {
        return Boolean.TRUE.equals(value);
    }

    private static Instant instantValue(Map<String, Object> source, String key, Instant defaultValue) {
        String value = nullableString(source.get(key));
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Instant.parse(value);
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private static long negativeStableId(String value) {
        UUID uuid = UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
        long positive = uuid.getMostSignificantBits() & Long.MAX_VALUE;
        return positive == 0 ? -1L : -positive;
    }

    private static final class ImportStats {
        private int pullRequestsCreated;
        private int pullRequestsUpdated;
        private int featuresCreated;
        private int featuresUpdated;
        private int workflowDeploymentsCreated;
        private int workflowDeploymentsUpdated;
    }

    private static final class AuthorHistory {
        private int totalPullRequests;
        private int mergedPullRequests;
    }
}
