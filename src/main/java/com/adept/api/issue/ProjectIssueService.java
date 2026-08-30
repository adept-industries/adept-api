package com.adept.api.issue;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.adept.api.common.domain.IssueState;
import com.adept.api.common.domain.MembershipRole;
import com.adept.api.common.domain.ProcessingJobStatus;
import com.adept.api.common.domain.ProcessingJobType;
import com.adept.api.common.error.NotFoundException;
import com.adept.api.common.error.ProblemCode;
import com.adept.api.integration.github.GitRepository;
import com.adept.api.integration.github.GithubIssue;
import com.adept.api.integration.github.GithubIssueRepository;
import com.adept.api.integration.jira.JiraIssue;
import com.adept.api.integration.jira.JiraIssueRepository;
import com.adept.api.integration.jira.JiraProject;
import com.adept.api.issue.dto.ProjectGithubIssuePageResponse;
import com.adept.api.issue.dto.ProjectGithubIssueResponse;
import com.adept.api.issue.dto.ProjectIssueSyncResponse;
import com.adept.api.issue.dto.ProjectJiraIssuePageResponse;
import com.adept.api.issue.dto.ProjectJiraIssueResponse;
import com.adept.api.job.ProcessingJob;
import com.adept.api.job.ProcessingJobRepository;
import com.adept.api.project.Project;
import com.adept.api.project.ProjectJiraProjectRepository;
import com.adept.api.project.ProjectRepository;
import com.adept.api.project.ProjectRepositoryLinkRepository;
import com.adept.api.security.AuthenticatedPrincipal;
import com.adept.api.workspace.WorkspaceAuthorizationService;

@Service
public class ProjectIssueService {

    private final ProjectRepository projectRepository;
    private final ProjectRepositoryLinkRepository linkRepository;
    private final ProjectJiraProjectRepository projectJiraProjectRepository;
    private final GithubIssueRepository githubIssueRepository;
    private final JiraIssueRepository jiraIssueRepository;
    private final ProcessingJobRepository processingJobRepository;
    private final WorkspaceAuthorizationService workspaceAuthorizationService;
    private final Clock clock;

    public ProjectIssueService(
            ProjectRepository projectRepository,
            ProjectRepositoryLinkRepository linkRepository,
            ProjectJiraProjectRepository projectJiraProjectRepository,
            GithubIssueRepository githubIssueRepository,
            JiraIssueRepository jiraIssueRepository,
            ProcessingJobRepository processingJobRepository,
            WorkspaceAuthorizationService workspaceAuthorizationService,
            Clock clock) {
        this.projectRepository = projectRepository;
        this.linkRepository = linkRepository;
        this.projectJiraProjectRepository = projectJiraProjectRepository;
        this.githubIssueRepository = githubIssueRepository;
        this.jiraIssueRepository = jiraIssueRepository;
        this.processingJobRepository = processingJobRepository;
        this.workspaceAuthorizationService = workspaceAuthorizationService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ProjectGithubIssuePageResponse listGithub(
            AuthenticatedPrincipal principal,
            UUID projectId,
            int page,
            int size) {
        ProjectAccess access = requireProjectAccess(principal, projectId);
        if (access.repositories().isEmpty()) {
            return githubResponse(Page.empty(PageRequest.of(page, size)), page, size);
        }
        Page<GithubIssue> issues = githubIssueRepository.findByProjectScope(
            principal.workspaceId(),
            access.repositories().stream().map(GitRepository::getId).toList(),
            IssueState.OPEN,
            PageRequest.of(page, size)
        );
        return githubResponse(issues, page, size);
    }

    @Transactional(readOnly = true)
    public ProjectJiraIssuePageResponse listJira(
            AuthenticatedPrincipal principal,
            UUID projectId,
            int page,
            int size) {
        ProjectAccess access = requireProjectAccess(principal, projectId);
        List<JiraProject> jiraProjects = trackedJiraProjects(access.project(), principal.workspaceId());
        if (jiraProjects.isEmpty()) {
            return jiraResponse(Page.empty(PageRequest.of(page, size)), page, size);
        }
        Page<JiraIssue> issues = jiraIssueRepository.findOpenByProjectScope(
            principal.workspaceId(),
            jiraProjects.stream().map(JiraProject::getId).toList(),
            PageRequest.of(page, size)
        );
        return jiraResponse(issues, page, size);
    }

    @Transactional
    public ProjectIssueSyncResponse sync(
            AuthenticatedPrincipal principal,
            UUID projectId) {
        workspaceAuthorizationService.requireManager(principal);
        ProjectAccess access = requireProjectAccess(principal, projectId);

        int queuedGithub = 0;
        int alreadyQueuedGithub = 0;
        for (GitRepository repository : access.repositories()) {
            if (processingJobRepository.existsActiveIssueBackfill(repository.getId())) {
                alreadyQueuedGithub++;
                continue;
            }
            ProcessingJob job = new ProcessingJob();
            job.setWorkspace(repository.getWorkspace());
            job.setRepository(repository);
            job.setJobType(ProcessingJobType.BACKFILL_REPOSITORY);
            job.setStatus(ProcessingJobStatus.PENDING);
            job.setPriority(45);
            job.setPayload(Map.of(
                "repositoryId", repository.getId().toString(),
                "issuesOnly", true
            ));
            job.setAvailableAt(clock.instant());
            processingJobRepository.save(job);
            queuedGithub++;
        }

        Map<UUID, JiraIntegrationGroup> jiraGroups = new LinkedHashMap<>();
        for (JiraProject jiraProject : trackedJiraProjects(access.project(), principal.workspaceId())) {
            UUID integrationId = jiraProject.getJiraIntegration().getId();
            jiraGroups.computeIfAbsent(
                integrationId,
                ignored -> new JiraIntegrationGroup(jiraProject, new ArrayList<>())
            ).jiraProjectIds().add(jiraProject.getId().toString());
        }

        int queuedJira = 0;
        int alreadyQueuedJira = 0;
        for (Map.Entry<UUID, JiraIntegrationGroup> entry : jiraGroups.entrySet()) {
            UUID integrationId = entry.getKey();
            if (processingJobRepository
                    .findActiveJiraIssueSyncForUpdate(integrationId.toString())
                    .isPresent()) {
                alreadyQueuedJira++;
                continue;
            }
            JiraIntegrationGroup group = entry.getValue();
            ProcessingJob job = new ProcessingJob();
            job.setWorkspace(group.firstProject().getWorkspace());
            job.setJobType(ProcessingJobType.SYNC_JIRA_PROJECTS);
            job.setStatus(ProcessingJobStatus.PENDING);
            job.setPriority(45);
            job.setPayload(Map.of(
                "workspaceId", principal.workspaceId().toString(),
                "jiraIntegrationId", integrationId.toString(),
                "jiraProjectIds", List.copyOf(group.jiraProjectIds()),
                "issuesOnly", true
            ));
            job.setAvailableAt(clock.instant());
            processingJobRepository.save(job);
            queuedJira++;
        }

        return new ProjectIssueSyncResponse(
            queuedGithub,
            alreadyQueuedGithub,
            queuedJira,
            alreadyQueuedJira
        );
    }

    private ProjectAccess requireProjectAccess(
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
        return new ProjectAccess(project, repositories);
    }

    private List<JiraProject> trackedJiraProjects(Project project, UUID workspaceId) {
        return projectJiraProjectRepository
            .findAllTrackedByProjectIdAndWorkspaceIdWithProject(project.getId(), workspaceId)
            .stream()
            .map(mapping -> mapping.getJiraProject())
            .toList();
    }

    private ProjectGithubIssuePageResponse githubResponse(
            Page<GithubIssue> issues,
            int requestedPage,
            int requestedSize) {
        return new ProjectGithubIssuePageResponse(
            issues.getContent().stream().map(this::githubItem).toList(),
            issues.hasContent() ? issues.getNumber() : requestedPage,
            issues.hasContent() ? issues.getSize() : requestedSize,
            issues.getTotalElements(),
            issues.getTotalPages()
        );
    }

    private ProjectGithubIssueResponse githubItem(GithubIssue issue) {
        GitRepository repository = issue.getRepository();
        return new ProjectGithubIssueResponse(
            issue.getId(),
            repository.getId(),
            repository.getFullName(),
            issue.getNumber(),
            issue.getTitle(),
            issue.getAuthorLogin(),
            issue.getAssigneeLogins() == null
                ? List.of()
                : List.copyOf(Arrays.asList(issue.getAssigneeLogins())),
            issue.getLabels() == null
                ? List.of()
                : List.copyOf(Arrays.asList(issue.getLabels())),
            issue.getCommentsCount(),
            "https://github.com/" + repository.getFullName() + "/issues/" + issue.getNumber(),
            issue.getGithubCreatedAt(),
            issue.getGithubUpdatedAt()
        );
    }

    private ProjectJiraIssuePageResponse jiraResponse(
            Page<JiraIssue> issues,
            int requestedPage,
            int requestedSize) {
        return new ProjectJiraIssuePageResponse(
            issues.getContent().stream().map(this::jiraItem).toList(),
            issues.hasContent() ? issues.getNumber() : requestedPage,
            issues.hasContent() ? issues.getSize() : requestedSize,
            issues.getTotalElements(),
            issues.getTotalPages()
        );
    }

    private ProjectJiraIssueResponse jiraItem(JiraIssue issue) {
        JiraProject jiraProject = issue.getJiraProject();
        String siteUrl = jiraProject.getJiraIntegration().getSiteUrl().replaceAll("/+$", "");
        return new ProjectJiraIssueResponse(
            issue.getId(),
            jiraProject.getId(),
            jiraProject.getProjectKey(),
            jiraProject.getProjectName(),
            issue.getIssueKey(),
            issue.getSummary(),
            issue.getIssueType(),
            issue.getStatusName(),
            issue.getPriorityName(),
            siteUrl + "/browse/" + issue.getIssueKey(),
            issue.getJiraCreatedAt(),
            issue.getJiraUpdatedAt()
        );
    }

    private record ProjectAccess(Project project, List<GitRepository> repositories) {
    }

    private record JiraIntegrationGroup(
        JiraProject firstProject,
        List<String> jiraProjectIds
    ) {
    }
}
