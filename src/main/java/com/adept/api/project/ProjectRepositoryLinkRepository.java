package com.adept.api.project;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProjectRepositoryLinkRepository
        extends JpaRepository<ProjectRepositoryLink, ProjectRepositoryLinkId> {

    @Query("""
        select link
        from ProjectRepositoryLink link
        join fetch link.repository repository
        where link.project.id = :projectId
        order by lower(repository.fullName), repository.id
        """)
    List<ProjectRepositoryLink> findAllWithRepositoryByProjectId(@Param("projectId") UUID projectId);

    @Query("""
        select link
        from ProjectRepositoryLink link
        join fetch link.repository repository
        join RepositoryLeadAssignment assignment on assignment.repository = repository
        where link.project.id = :projectId
          and assignment.leadMembership.id = :membershipId
          and repository.trackingEnabled = true
          and repository.archived = false
        order by lower(repository.fullName), repository.id
        """)
    List<ProjectRepositoryLink> findAllReadableByLead(
        @Param("projectId") UUID projectId,
        @Param("membershipId") UUID membershipId
    );

    @Modifying
    @Query("delete from ProjectRepositoryLink link where link.project.id = :projectId")
    void deleteAllByProjectId(@Param("projectId") UUID projectId);
}
