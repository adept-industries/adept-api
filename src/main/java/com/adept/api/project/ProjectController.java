package com.adept.api.project;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.adept.api.auth.AccountRequestContext;
import com.adept.api.project.dto.CreateProjectRequest;
import com.adept.api.project.dto.ProjectResponse;
import com.adept.api.project.dto.ReplaceProjectRepositoriesRequest;
import com.adept.api.project.dto.UpdateProjectRequest;
import com.adept.api.security.AuthenticatedPrincipal;
import com.adept.api.security.CurrentPrincipal;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

@Validated
@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {

    private final ProjectService projectService;
    private final CurrentPrincipal currentPrincipal;

    public ProjectController(ProjectService projectService, CurrentPrincipal currentPrincipal) {
        this.projectService = projectService;
        this.currentPrincipal = currentPrincipal;
    }

    @GetMapping
    public ResponseEntity<List<ProjectResponse>> list() {
        return ResponseEntity.ok(projectService.list(currentPrincipal.require()));
    }

    @PostMapping
    public ResponseEntity<ProjectResponse> create(
            @Valid @RequestBody CreateProjectRequest request,
            HttpServletRequest servletRequest) {
        AuthenticatedPrincipal principal = currentPrincipal.require();
        ProjectResponse response = projectService.create(
            principal,
            request,
            AccountRequestContext.from(servletRequest)
        );
        return ResponseEntity.created(URI.create("/api/v1/projects/" + response.id())).body(response);
    }

    @GetMapping("/{projectId}")
    public ResponseEntity<ProjectResponse> get(@PathVariable UUID projectId) {
        return ResponseEntity.ok(projectService.get(currentPrincipal.require(), projectId));
    }

    @PatchMapping("/{projectId}")
    public ResponseEntity<ProjectResponse> update(
            @PathVariable UUID projectId,
            @RequestBody UpdateProjectRequest request,
            HttpServletRequest servletRequest) {
        return ResponseEntity.ok(projectService.update(
            currentPrincipal.require(),
            projectId,
            request,
            AccountRequestContext.from(servletRequest)
        ));
    }

    @PutMapping("/{projectId}/repositories")
    public ResponseEntity<ProjectResponse> replaceRepositories(
            @PathVariable UUID projectId,
            @Valid @RequestBody ReplaceProjectRepositoriesRequest request,
            HttpServletRequest servletRequest) {
        return ResponseEntity.ok(projectService.replaceRepositories(
            currentPrincipal.require(),
            projectId,
            request,
            AccountRequestContext.from(servletRequest)
        ));
    }

    @DeleteMapping("/{projectId}")
    public ResponseEntity<Void> delete(
            @PathVariable UUID projectId,
            HttpServletRequest servletRequest) {
        projectService.delete(
            currentPrincipal.require(),
            projectId,
            AccountRequestContext.from(servletRequest)
        );
        return ResponseEntity.noContent().build();
    }
}
