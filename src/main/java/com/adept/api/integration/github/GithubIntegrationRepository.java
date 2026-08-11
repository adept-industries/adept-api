package com.adept.api.integration.github;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface GithubIntegrationRepository extends JpaRepository<GithubIntegration, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select integration
        from GithubIntegration integration
        where integration.workspace.id = :workspaceId
        order by integration.id asc
        """)
    List<GithubIntegration> findAllByWorkspaceIdForUpdate(@Param("workspaceId") UUID workspaceId);
}
