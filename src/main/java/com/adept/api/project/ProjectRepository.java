package com.adept.api.project;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProjectRepository extends JpaRepository<Project, UUID> {

    List<Project> findAllByWorkspaceIdOrderByNameAscIdAsc(UUID workspaceId);

    Optional<Project> findByIdAndWorkspaceId(UUID id, UUID workspaceId);

    boolean existsByWorkspaceIdAndNameIgnoreCase(UUID workspaceId, String name);

    boolean existsByWorkspaceIdAndNameIgnoreCaseAndIdNot(UUID workspaceId, String name, UUID id);

    @Query("""
        select distinct p
        from Project p
        join ProjectRepositoryLink link on link.project = p
        join RepositoryLeadAssignment assignment on assignment.repository = link.repository
        where p.workspace.id = :workspaceId
          and assignment.leadMembership.id = :membershipId
          and link.repository.trackingEnabled = true
          and link.repository.archived = false
        order by p.name, p.id
        """)
    List<Project> findAllVisibleToLead(
        @Param("workspaceId") UUID workspaceId,
        @Param("membershipId") UUID membershipId
    );
}
