package com.adept.api.chat;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProjectChatMessageRepository extends JpaRepository<ProjectChatMessage, UUID> {

    @Query("""
        select m
        from ProjectChatMessage m
        join fetch m.senderMembership sm
        join fetch sm.user
        where m.workspace.id = :workspaceId
          and m.project.id = :projectId
        order by m.createdAt asc
        """)
    List<ProjectChatMessage> findAllWithSenderByProjectId(
        @Param("workspaceId") UUID workspaceId,
        @Param("projectId") UUID projectId
    );
}
