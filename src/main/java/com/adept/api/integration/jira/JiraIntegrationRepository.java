package com.adept.api.integration.jira;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface JiraIntegrationRepository extends JpaRepository<JiraIntegration, UUID> {

    List<JiraIntegration> findAllByWorkspaceId(UUID workspaceId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select integration
        from JiraIntegration integration
        where integration.workspace.id = :workspaceId
        order by integration.id asc
        """)
    List<JiraIntegration> findAllByWorkspaceIdForUpdate(@Param("workspaceId") UUID workspaceId);
}
