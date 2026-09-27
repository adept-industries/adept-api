package com.adept.api.chat;

import com.adept.api.common.domain.BaseEntity;
import com.adept.api.project.Project;
import com.adept.api.workspace.Membership;
import com.adept.api.workspace.Workspace;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "project_chat_messages")
public class ProjectChatMessage extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private Workspace workspace;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sender_membership_id", nullable = false)
    private Membership senderMembership;

    @Column(name = "content_enc", nullable = false, columnDefinition = "text")
    private String contentEnc;

    @Column(name = "encryption_key_version", nullable = false)
    private int encryptionKeyVersion;
}
