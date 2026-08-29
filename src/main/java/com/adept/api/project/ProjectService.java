package com.adept.api.project;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.adept.api.audit.AuditAction;
import com.adept.api.audit.AuditService;
import com.adept.api.auth.AccountRequestContext;
import com.adept.api.common.domain.MembershipRole;
import com.adept.api.common.error.ApiException;
import com.adept.api.common.error.ConflictException;
import com.adept.api.common.error.NotFoundException;
import com.adept.api.common.error.ProblemCode;
import com.adept.api.integration.github.GitRepository;
import com.adept.api.integration.github.GitRepositoryRepository;
import com.adept.api.integration.jira.JiraProject;
import com.adept.api.integration.jira.JiraProjectRepository;
import com.adept.api.integration.jira.RepositoryJiraProject;
import com.adept.api.integration.jira.RepositoryJiraProjectRepository;
import com.adept.api.project.dto.CreateProjectRequest;
import com.adept.api.project.dto.ProjectJiraProjectResponse;
import com.adept.api.project.dto.ProjectRepositoryConfigurationRequest;
import com.adept.api.project.dto.ProjectRepositoryResponse;
import com.adept.api.project.dto.ProjectResponse;
import com.adept.api.project.dto.ReplaceProjectConfigurationRequest;
import com.adept.api.project.dto.ReplaceProjectRepositoriesRequest;
import com.adept.api.project.dto.UpdateProjectRequest;
import com.adept.api.security.AuthenticatedPrincipal;
import com.adept.api.workspace.Membership;
import com.adept.api.workspace.MembershipRepository;
import com.adept.api.workspace.WorkspaceAuthorizationService;

@Service
@Transactional
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final ProjectRepositoryLinkRepository linkRepository;
    private final GitRepositoryRepository gitRepositoryRepository;
    private final JiraProjectRepository jiraProjectRepository;
    private final RepositoryJiraProjectRepository repositoryJiraProjectRepository;
    private final ProjectJiraProjectRepository projectJiraProjectRepository;
    private final MembershipRepository membershipRepository;
    private final WorkspaceAuthorizationService workspaceAuthorizationService;
    private final AuditService auditService;
    private final Clock clock;

    public ProjectService(
            ProjectRepository projectRepository,
            ProjectRepositoryLinkRepository linkRepository,
            GitRepositoryRepository gitRepositoryRepository,
            JiraProjectRepository jiraProjectRepository,
            RepositoryJiraProjectRepository repositoryJiraProjectRepository,
            ProjectJiraProjectRepository projectJiraProjectRepository,
            MembershipRepository membershipRepository,
            WorkspaceAuthorizationService workspaceAuthorizationService,
            AuditService auditService,
            Clock clock) {
        this.projectRepository = projectRepository;
        this.linkRepository = linkRepository;
        this.gitRepositoryRepository = gitRepositoryRepository;
        this.jiraProjectRepository = jiraProjectRepository;
        this.repositoryJiraProjectRepository = repositoryJiraProjectRepository;
        this.projectJiraProjectRepository = projectJiraProjectRepository;
        this.membershipRepository = membershipRepository;
        this.workspaceAuthorizationService = workspaceAuthorizationService;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<ProjectResponse> list(AuthenticatedPrincipal principal) {
        Membership membership = requireCurrentMembership(principal);
        List<Project> projects = principal.role() == MembershipRole.MANAGER
            ? projectRepository.findAllByWorkspaceIdOrderByNameAscIdAsc(principal.workspaceId())
            : projectRepository.findAllVisibleToLead(principal.workspaceId(), principal.membershipId());
        return projects.stream()
            .map(project -> response(project, membership))
            .toList();
    }

    @Transactional(readOnly = true)
    public ProjectResponse get(AuthenticatedPrincipal principal, UUID projectId) {
        Membership membership = requireCurrentMembership(principal);
        Project project = requireVisibleProject(principal, projectId);
        return response(project, membership);
    }

    public ProjectResponse create(
            AuthenticatedPrincipal principal,
            CreateProjectRequest request,
            AccountRequestContext context) {
        workspaceAuthorizationService.requireManager(principal);
        Membership membership = requireCurrentMembership(principal);
        String name = request.name().trim();
        if (projectRepository.existsByWorkspaceIdAndNameIgnoreCase(principal.workspaceId(), name)) {
            throw new ConflictException(ProblemCode.PROJECT_CONFLICT);
        }
        ResolvedConfiguration configuration = resolveConfiguration(
            principal.workspaceId(),
            request.repositories(),
            request.jiraProjectIds()
        );

        Project project = new Project();
        project.setWorkspace(membership.getWorkspace());
        project.setName(name);
        project.setDescription(normalizeDescription(request.description()));
        project.setCreatedByMembership(membership);
        try {
            project = projectRepository.saveAndFlush(project);
        } catch (DataIntegrityViolationException exception) {
            throw new ConflictException(ProblemCode.PROJECT_CONFLICT);
        }

        audit(AuditAction.PROJECT_CREATED, project, membership, Map.of(), context);
        if (!configuration.repositories().isEmpty() || !configuration.jiraProjects().isEmpty()) {
            applyConfiguration(project, configuration, membership, context);
        }
        return response(project, membership);
    }

    public ProjectResponse update(
            AuthenticatedPrincipal principal,
            UUID projectId,
            UpdateProjectRequest request,
            AccountRequestContext context) {
        workspaceAuthorizationService.requireManager(principal);
        Membership membership = requireCurrentMembership(principal);
        request.validate();
        Project project = requireProject(principal.workspaceId(), projectId);
        List<String> changedFields = new ArrayList<>();

        if (request.isNamePresent()) {
            String name = request.getName().trim();
            if (!name.equals(project.getName())) {
                if (projectRepository.existsByWorkspaceIdAndNameIgnoreCaseAndIdNot(
                        principal.workspaceId(), name, project.getId())) {
                    throw new ConflictException(ProblemCode.PROJECT_CONFLICT);
                }
                project.setName(name);
                changedFields.add("name");
            }
        }
        if (request.isDescriptionPresent()) {
            String description = normalizeDescription(request.getDescription());
            if (!java.util.Objects.equals(description, project.getDescription())) {
                project.setDescription(description);
                changedFields.add("description");
            }
        }

        try {
            project = projectRepository.saveAndFlush(project);
        } catch (DataIntegrityViolationException exception) {
            throw new ConflictException(ProblemCode.PROJECT_CONFLICT);
        }
        if (!changedFields.isEmpty()) {
            audit(AuditAction.PROJECT_UPDATED, project, membership, Map.of("changedFields", changedFields), context);
        }
        return response(project, membership);
    }

    public ProjectResponse replaceRepositories(
            AuthenticatedPrincipal principal,
            UUID projectId,
            ReplaceProjectRepositoriesRequest request,
            AccountRequestContext context) {
        workspaceAuthorizationService.requireManager(principal);
        Membership membership = requireCurrentMembership(principal);
        Project project = requireProject(principal.workspaceId(), projectId);
        Set<UUID> requestedIds = new HashSet<>(request.repositoryIds());
        List<GitRepository> repositories = requireAttachableRepositories(principal.workspaceId(), requestedIds);
        replaceProjectLinks(project, repositories);

        audit(
            AuditAction.PROJECT_REPOSITORIES_UPDATED,
            project,
            membership,
            Map.of("repositoryCount", repositories.size()),
            context
        );
        return response(project, membership);
    }

    public ProjectResponse replaceConfiguration(
            AuthenticatedPrincipal principal,
            UUID projectId,
            ReplaceProjectConfigurationRequest request,
            AccountRequestContext context) {
        workspaceAuthorizationService.requireManager(principal);
        Membership membership = requireCurrentMembership(principal);
        Project project = requireProject(principal.workspaceId(), projectId);
        ResolvedConfiguration configuration = resolveConfiguration(
            principal.workspaceId(),
            request.repositories(),
            request.jiraProjectIds()
        );
        applyConfiguration(project, configuration, membership, context);
        return response(project, membership);
    }

    public void delete(
            AuthenticatedPrincipal principal,
            UUID projectId,
            AccountRequestContext context) {
        workspaceAuthorizationService.requireManager(principal);
        Membership membership = requireCurrentMembership(principal);
        Project project = requireProject(principal.workspaceId(), projectId);
        audit(AuditAction.PROJECT_DELETED, project, membership, Map.of(), context);
        projectRepository.delete(project);
    }

    private Project requireVisibleProject(AuthenticatedPrincipal principal, UUID projectId) {
        Project project = requireProject(principal.workspaceId(), projectId);
        if (principal.role() == MembershipRole.MANAGER) {
            return project;
        }
        boolean visible = projectRepository.findAllVisibleToLead(principal.workspaceId(), principal.membershipId())
            .stream()
            .anyMatch(candidate -> candidate.getId().equals(projectId));
        if (!visible) {
            throw new NotFoundException(ProblemCode.PROJECT_NOT_FOUND);
        }
        return project;
    }

    private Project requireProject(UUID workspaceId, UUID projectId) {
        if (projectId == null) {
            throw new NotFoundException(ProblemCode.PROJECT_NOT_FOUND);
        }
        return projectRepository.findByIdAndWorkspaceId(projectId, workspaceId)
            .orElseThrow(() -> new NotFoundException(ProblemCode.PROJECT_NOT_FOUND));
    }

    private Membership requireCurrentMembership(AuthenticatedPrincipal principal) {
        return membershipRepository.findActiveByUserIdAndWorkspaceId(principal.userId(), principal.workspaceId())
            .filter(membership -> membership.getId().equals(principal.membershipId()))
            .orElseThrow(() -> new NotFoundException(ProblemCode.PROJECT_NOT_FOUND));
    }

    private ProjectResponse response(Project project, Membership membership) {
        List<ProjectRepositoryLink> links = membership.getRole() == MembershipRole.MANAGER
            ? linkRepository.findAllWithRepositoryByProjectId(project.getId())
            : linkRepository.findAllReadableByLead(project.getId(), membership.getId());
        List<ProjectJiraProjectResponse> jiraProjects = projectJiraProjectRepository
            .findAllTrackedByProjectIdAndWorkspaceIdWithProject(project.getId(), project.getWorkspace().getId())
            .stream()
            .map(mapping -> ProjectJiraProjectResponse.from(mapping.getJiraProject()))
            .toList();
        if (jiraProjects.isEmpty() && !links.isEmpty()) {
            jiraProjects = repositoryJiraProjectRepository
                .findAllByRepositoryIdsAndWorkspaceIdWithProject(
                    links.stream().map(link -> link.getRepository().getId()).toList(),
                    project.getWorkspace().getId()
                )
                .stream()
                .map(RepositoryJiraProject::getJiraProject)
                .filter(JiraProject::isTrackingEnabled)
                .collect(Collectors.toMap(
                    JiraProject::getId,
                    ProjectJiraProjectResponse::from,
                    (first, ignored) -> first,
                    LinkedHashMap::new
                ))
                .values()
                .stream()
                .toList();
        }
        List<ProjectJiraProjectResponse> visibleJiraProjects = jiraProjects;
        List<ProjectRepositoryResponse> repositories = links.stream()
            .map(ProjectRepositoryLink::getRepository)
            // Temporary compatibility: the deployed frontend still reads Jira
            // mappings from each repository until its project-level PR lands.
            .map(repository -> ProjectRepositoryResponse.from(repository, visibleJiraProjects))
            .toList();
        return ProjectResponse.from(project, repositories, visibleJiraProjects);
    }

    private ResolvedConfiguration resolveConfiguration(
            UUID workspaceId,
            List<ProjectRepositoryConfigurationRequest> requestedConfiguration,
            Set<UUID> requestedProjectJiraIds) {
        Map<UUID, Set<UUID>> jiraProjectIdsByRepository = new LinkedHashMap<>();
        for (ProjectRepositoryConfigurationRequest configuredRepository :
                requestedConfiguration == null ? List.<ProjectRepositoryConfigurationRequest>of() : requestedConfiguration) {
            if (configuredRepository == null
                    || configuredRepository.repositoryId() == null
                    || configuredRepository.jiraProjectIds() == null
                    || configuredRepository.jiraProjectIds().contains(null)) {
                throw new ApiException(ProblemCode.VALIDATION_FAILED, "Project repository configuration is incomplete.");
            }
            Set<UUID> previous = jiraProjectIdsByRepository.putIfAbsent(
                configuredRepository.repositoryId(),
                Set.copyOf(configuredRepository.jiraProjectIds())
            );
            if (previous != null) {
                throw new ApiException(
                    ProblemCode.VALIDATION_FAILED,
                    "Each repository can appear only once in project configuration."
                );
            }
        }

        Set<UUID> repositoryIds = jiraProjectIdsByRepository.keySet();
        List<GitRepository> repositories = requireAttachableRepositories(workspaceId, repositoryIds);
        Map<UUID, GitRepository> repositoryById = repositories.stream()
            .collect(Collectors.toMap(GitRepository::getId, repository -> repository));

        boolean legacyRequest = requestedProjectJiraIds == null;
        Set<UUID> allJiraProjectIds = legacyRequest
            ? jiraProjectIdsByRepository.values().stream().flatMap(Set::stream).collect(Collectors.toSet())
            : Set.copyOf(requestedProjectJiraIds);
        List<JiraProject> jiraProjects = allJiraProjectIds.isEmpty()
            ? List.of()
            : jiraProjectRepository.findAllByIdInAndWorkspaceId(allJiraProjectIds, workspaceId);
        if (jiraProjects.size() != allJiraProjectIds.size()) {
            throw new NotFoundException(
                ProblemCode.JIRA_PROJECT_NOT_FOUND,
                "One or more Jira projects do not exist or belong to another workspace."
            );
        }
        if (jiraProjects.stream().anyMatch(project -> !project.isTrackingEnabled())) {
            throw new ApiException(
                ProblemCode.VALIDATION_FAILED,
                "Only tracked Jira projects can be mapped to an Adept project."
            );
        }
        Map<UUID, JiraProject> jiraProjectById = jiraProjects.stream()
            .collect(Collectors.toMap(JiraProject::getId, jiraProject -> jiraProject));
        Map<UUID, List<JiraProject>> legacyMappings = new LinkedHashMap<>();
        if (legacyRequest) {
            jiraProjectIdsByRepository.forEach((repositoryId, projectIds) -> legacyMappings.put(
                repositoryId,
                projectIds.stream().map(jiraProjectById::get).toList()
            ));
        }
        return new ResolvedConfiguration(repositories, jiraProjects, legacyMappings, legacyRequest);
    }

    private List<GitRepository> requireAttachableRepositories(UUID workspaceId, Set<UUID> repositoryIds) {
        if (repositoryIds.isEmpty()) {
            return List.of();
        }
        List<GitRepository> repositories = gitRepositoryRepository.findAllByIdInAndWorkspaceId(
            repositoryIds,
            workspaceId
        );
        if (repositories.size() != repositoryIds.size()) {
            throw new NotFoundException(ProblemCode.REPOSITORY_NOT_FOUND);
        }
        if (repositories.stream().anyMatch(repository -> !repository.isTrackingEnabled() || repository.isArchived())) {
            throw new ApiException(
                ProblemCode.VALIDATION_FAILED,
                "Only tracked, non-archived repositories can be attached to a project."
            );
        }
        return repositories;
    }

    private void applyConfiguration(
            Project project,
            ResolvedConfiguration configuration,
            Membership membership,
            AccountRequestContext context) {
        List<GitRepository> repositories = configuration.repositories();
        replaceProjectLinks(project, repositories);

        projectJiraProjectRepository.deleteAllByProjectId(project.getId());
        projectJiraProjectRepository.flush();
        projectJiraProjectRepository.saveAll(configuration.jiraProjects().stream()
            .map(jiraProject -> ProjectJiraProject.create(project, jiraProject, clock.instant()))
            .toList());
        projectJiraProjectRepository.flush();

        if (!repositories.isEmpty()) {
            Set<UUID> configuredRepositoryIds = repositories.stream()
                .map(GitRepository::getId)
                .collect(Collectors.toSet());
            repositoryJiraProjectRepository.deleteAllByRepositoryIds(configuredRepositoryIds);
            repositoryJiraProjectRepository.flush();
            if (configuration.legacyRequest()) {
                List<RepositoryJiraProject> mappings = repositories.stream()
                    .flatMap(repository -> configuration.legacyMappings()
                        .getOrDefault(repository.getId(), List.of())
                        .stream()
                        .map(jiraProject -> RepositoryJiraProject.create(repository, jiraProject, clock.instant())))
                    .toList();
                repositoryJiraProjectRepository.saveAll(mappings);
                repositoryJiraProjectRepository.flush();
            }
        }
        audit(AuditAction.PROJECT_JIRA_PROJECTS_UPDATED, project, membership,
            Map.of("jiraProjectCount", configuration.jiraProjects().size()), context);
        audit(
            AuditAction.PROJECT_REPOSITORIES_UPDATED,
            project,
            membership,
            Map.of(
                "repositoryCount", repositories.size(),
                "jiraMappingCount", configuration.jiraProjects().size()
            ),
            context
        );
    }

    private void replaceProjectLinks(Project project, List<GitRepository> repositories) {
        linkRepository.deleteAllByProjectId(project.getId());
        linkRepository.flush();
        List<ProjectRepositoryLink> links = repositories.stream().map(repository -> {
            ProjectRepositoryLink link = new ProjectRepositoryLink();
            link.setId(new ProjectRepositoryLinkId(project.getId(), repository.getId()));
            link.setProject(project);
            link.setRepository(repository);
            link.setWorkspace(project.getWorkspace());
            return link;
        }).toList();
        linkRepository.saveAll(links);
        linkRepository.flush();
    }

    private void audit(
            AuditAction action,
            Project project,
            Membership membership,
            Map<String, Object> metadata,
            AccountRequestContext context) {
        auditService.record(
            action,
            membership.getUser(),
            membership,
            membership.getWorkspace(),
            "PROJECT",
            project.getId(),
            metadata,
            context != null ? context.ipAddress() : null,
            context != null ? context.userAgent() : null
        );
    }

    private static String normalizeDescription(String description) {
        if (description == null || description.isBlank()) {
            return null;
        }
        return description.trim();
    }

    private record ResolvedConfiguration(
        List<GitRepository> repositories,
        List<JiraProject> jiraProjects,
        Map<UUID, List<JiraProject>> legacyMappings,
        boolean legacyRequest) {
    }
}
