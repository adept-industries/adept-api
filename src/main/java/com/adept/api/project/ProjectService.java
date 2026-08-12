package com.adept.api.project;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.adept.api.audit.AuditAction;
import com.adept.api.audit.AuditService;
import com.adept.api.auth.AccountRequestContext;
import com.adept.api.common.domain.MembershipRole;
import com.adept.api.common.error.ConflictException;
import com.adept.api.common.error.NotFoundException;
import com.adept.api.common.error.ProblemCode;
import com.adept.api.integration.github.GitRepository;
import com.adept.api.integration.github.GitRepositoryRepository;
import com.adept.api.project.dto.CreateProjectRequest;
import com.adept.api.project.dto.ProjectRepositoryResponse;
import com.adept.api.project.dto.ProjectResponse;
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
    private final MembershipRepository membershipRepository;
    private final WorkspaceAuthorizationService workspaceAuthorizationService;
    private final AuditService auditService;

    public ProjectService(
            ProjectRepository projectRepository,
            ProjectRepositoryLinkRepository linkRepository,
            GitRepositoryRepository gitRepositoryRepository,
            MembershipRepository membershipRepository,
            WorkspaceAuthorizationService workspaceAuthorizationService,
            AuditService auditService) {
        this.projectRepository = projectRepository;
        this.linkRepository = linkRepository;
        this.gitRepositoryRepository = gitRepositoryRepository;
        this.membershipRepository = membershipRepository;
        this.workspaceAuthorizationService = workspaceAuthorizationService;
        this.auditService = auditService;
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
        return ProjectResponse.from(project, List.of());
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
        List<GitRepository> repositories = requestedIds.isEmpty()
            ? List.of()
            : gitRepositoryRepository.findAllByIdInAndWorkspaceId(requestedIds, principal.workspaceId());
        if (repositories.size() != requestedIds.size()) {
            throw new NotFoundException(ProblemCode.REPOSITORY_NOT_FOUND);
        }

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

        audit(
            AuditAction.PROJECT_REPOSITORIES_UPDATED,
            project,
            membership,
            Map.of("repositoryCount", repositories.size()),
            context
        );
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
        List<ProjectRepositoryResponse> repositories = links.stream()
            .map(ProjectRepositoryLink::getRepository)
            .map(ProjectRepositoryResponse::from)
            .toList();
        return ProjectResponse.from(project, repositories);
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
}
