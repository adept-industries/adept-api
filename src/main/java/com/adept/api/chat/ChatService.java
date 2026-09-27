package com.adept.api.chat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.adept.api.chat.dto.ChatMessageResponse;
import com.adept.api.chat.dto.ChatMessageSenderDto;
import com.adept.api.chat.dto.ProjectTeamResponse;
import com.adept.api.chat.dto.TeamMemberDto;
import com.adept.api.common.domain.MembershipRole;
import com.adept.api.common.domain.MembershipStatus;
import com.adept.api.crypto.IntegrationEncryptionService;
import com.adept.api.project.Project;
import com.adept.api.project.ProjectRepositoryLinkRepository;
import com.adept.api.project.ProjectService;
import com.adept.api.security.AuthenticatedPrincipal;
import com.adept.api.workspace.Membership;

@Service
@Transactional
public class ChatService {

    private final ProjectService projectService;
    private final ProjectChatMessageRepository chatMessageRepository;
    private final ProjectRepositoryLinkRepository linkRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final IntegrationEncryptionService encryptionService;

    public ChatService(
            ProjectService projectService,
            ProjectChatMessageRepository chatMessageRepository,
            ProjectRepositoryLinkRepository linkRepository,
            SimpMessagingTemplate messagingTemplate,
            IntegrationEncryptionService encryptionService) {
        this.projectService = projectService;
        this.chatMessageRepository = chatMessageRepository;
        this.linkRepository = linkRepository;
        this.messagingTemplate = messagingTemplate;
        this.encryptionService = encryptionService;
    }

    @Transactional(readOnly = true)
    public ProjectTeamResponse getTeam(AuthenticatedPrincipal principal, UUID projectId) {
        Project project = projectService.requireVisibleProject(principal, projectId);

        Membership managerMembership = project.getCreatedByMembership();
        TeamMemberDto managerDto = null;
        if (managerMembership != null
                && managerMembership.getStatus() == MembershipStatus.ACTIVE
                && managerMembership.getUser() != null) {
            managerDto = new TeamMemberDto(
                managerMembership.getId(),
                managerMembership.getUser().getId(),
                managerMembership.getUser().getDisplayName(),
                managerMembership.getUser().getEmail(),
                managerMembership.getUser().getAvatarUrl(),
                MembershipRole.MANAGER
            );
        } else if (principal.role() == MembershipRole.MANAGER) {
            Membership currentManager = projectService.requireCurrentMembership(principal);
            if (currentManager.getUser() != null) {
                managerDto = new TeamMemberDto(
                    currentManager.getId(),
                    currentManager.getUser().getId(),
                    currentManager.getUser().getDisplayName(),
                    currentManager.getUser().getEmail(),
                    currentManager.getUser().getAvatarUrl(),
                    MembershipRole.MANAGER
                );
            }
        }

        List<Membership> leadMemberships = linkRepository.findActiveLeadMembershipsByProjectId(projectId);
        List<Membership> sortedLeads = leadMemberships.stream()
            .sorted(Comparator.comparing(
                (Membership m) -> m.getUser() != null && m.getUser().getDisplayName() != null
                    ? m.getUser().getDisplayName()
                    : "",
                String.CASE_INSENSITIVE_ORDER
            ).thenComparing(Membership::getId))
            .toList();

        Map<UUID, TeamMemberDto> memberMap = new LinkedHashMap<>();
        if (managerDto != null) {
            memberMap.put(managerDto.membershipId(), managerDto);
        }
        for (Membership lead : sortedLeads) {
            if (lead.getUser() != null) {
                memberMap.putIfAbsent(lead.getId(), new TeamMemberDto(
                    lead.getId(),
                    lead.getUser().getId(),
                    lead.getUser().getDisplayName(),
                    lead.getUser().getEmail(),
                    lead.getUser().getAvatarUrl(),
                    MembershipRole.LEAD
                ));
            }
        }

        return new ProjectTeamResponse(
            project.getId(),
            project.getName(),
            project.getDescription(),
            managerDto,
            new ArrayList<>(memberMap.values())
        );
    }

    @Transactional(readOnly = true)
    public List<ChatMessageResponse> getMessages(AuthenticatedPrincipal principal, UUID projectId) {
        Project project = projectService.requireVisibleProject(principal, projectId);
        List<ProjectChatMessage> messages = chatMessageRepository.findAllWithSenderByProjectId(
            principal.workspaceId(),
            project.getId()
        );
        return messages.stream().map(this::toResponse).toList();
    }

    public ChatMessageResponse sendMessage(AuthenticatedPrincipal principal, UUID projectId, String content) {
        Project project = projectService.requireVisibleProject(principal, projectId);
        Membership sender = projectService.requireCurrentMembership(principal);

        String trimmedContent = content != null ? content.trim() : "";
        var encryptedPayload = encryptionService.encrypt(trimmedContent);

        ProjectChatMessage message = new ProjectChatMessage();
        message.setWorkspace(sender.getWorkspace());
        message.setProject(project);
        message.setSenderMembership(sender);
        message.setContentEnc(encryptedPayload.ciphertext());
        message.setEncryptionKeyVersion(encryptedPayload.keyVersion());

        ProjectChatMessage saved = chatMessageRepository.save(message);
        ChatMessageResponse response = toResponse(saved, trimmedContent);

        // Broadcast real-time message via WebSocket topic to all project team members
        messagingTemplate.convertAndSend("/topic/projects/" + projectId, response);

        return response;
    }

    private ChatMessageResponse toResponse(ProjectChatMessage message) {
        String decryptedContent = encryptionService.decrypt(
            message.getContentEnc(),
            message.getEncryptionKeyVersion()
        );
        return toResponse(message, decryptedContent);
    }

    private ChatMessageResponse toResponse(ProjectChatMessage message, String decryptedContent) {
        Membership sender = message.getSenderMembership();
        ChatMessageSenderDto senderDto = new ChatMessageSenderDto(
            sender.getId(),
            sender.getUser() != null ? sender.getUser().getId() : null,
            sender.getUser() != null ? sender.getUser().getDisplayName() : "Unknown",
            sender.getUser() != null ? sender.getUser().getEmail() : "",
            sender.getUser() != null ? sender.getUser().getAvatarUrl() : null,
            sender.getRole()
        );
        return new ChatMessageResponse(
            message.getId(),
            message.getProject().getId(),
            decryptedContent,
            message.getCreatedAt(),
            senderDto
        );
    }
}
