package com.adept.api.alert;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AlertRuleRepository extends JpaRepository<AlertRule, UUID> {

    @Query("""
        SELECT r FROM AlertRule r
        JOIN FETCH r.repository repo
        JOIN FETCH r.workspace w
        LEFT JOIN FETCH r.createdBy cb
        WHERE r.id = :id AND w.id = :workspaceId
        """)
    Optional<AlertRule> findByIdAndWorkspaceId(@Param("id") UUID id, @Param("workspaceId") UUID workspaceId);

    @Query("""
        SELECT r FROM AlertRule r
        JOIN FETCH r.repository repo
        JOIN FETCH r.workspace w
        LEFT JOIN FETCH r.createdBy cb
        WHERE w.id = :workspaceId
        ORDER BY lower(r.name) ASC, r.id ASC
        """)
    List<AlertRule> findAllByWorkspaceId(@Param("workspaceId") UUID workspaceId);

    @Query("""
        SELECT r FROM AlertRule r
        JOIN FETCH r.repository repo
        JOIN FETCH r.workspace w
        LEFT JOIN FETCH r.createdBy cb
        WHERE w.id = :workspaceId AND repo.id = :repositoryId
        ORDER BY lower(r.name) ASC, r.id ASC
        """)
    List<AlertRule> findAllByWorkspaceIdAndRepositoryId(
        @Param("workspaceId") UUID workspaceId,
        @Param("repositoryId") UUID repositoryId
    );

    @Query("""
        SELECT r FROM AlertRule r
        JOIN FETCH r.repository repo
        JOIN FETCH r.workspace w
        LEFT JOIN FETCH r.createdBy cb
        WHERE w.id = :workspaceId AND repo.id IN :repositoryIds
        ORDER BY lower(r.name) ASC, r.id ASC
        """)
    List<AlertRule> findAllByWorkspaceIdAndRepositoryIdIn(
        @Param("workspaceId") UUID workspaceId,
        @Param("repositoryIds") Collection<UUID> repositoryIds
    );
}
