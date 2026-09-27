package com.adept.api.chat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import com.adept.api.chat.dto.ChatMessageResponse;
import com.adept.api.chat.dto.ProjectTeamResponse;
import com.adept.api.common.domain.MembershipRole;
import com.adept.api.common.domain.MembershipStatus;
import com.adept.api.crypto.IntegrationEncryptionService;
import com.adept.api.project.Project;
import com.adept.api.project.ProjectRepositoryLinkRepository;
import com.adept.api.project.ProjectService;
import com.adept.api.security.AuthenticatedPrincipal;
import com.adept.api.user.User;
import com.adept.api.workspace.Membership;
import com.adept.api.workspace.Workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    @Mock
    private ProjectService projectService;

    @Mock
    private ProjectChatMessageRepository chatMessageRepository;

    @Mock
    private ProjectRepositoryLinkRepository linkRepository;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Mock
    private IntegrationEncryptionService encryptionService;

    private ChatService chatService;

    private AuthenticatedPrincipal principal;
    private Workspace workspace;
    private Project project;
    private Membership managerMembership;
    private Membership leadMembership;

    @BeforeEach
    void setUp() {
        chatService = new ChatService(projectService, chatMessageRepository, linkRepository, messagingTemplate, encryptionService);

        workspace = new Workspace();
        workspace.setId(UUID.randomUUID());
        workspace.setName("Adept Org");

        User managerUser = new User();
        managerUser.setId(UUID.randomUUID());
        managerUser.setDisplayName("Alice Manager");
        managerUser.setEmail("alice@adept.test");

        managerMembership = new Membership();
        managerMembership.setId(UUID.randomUUID());
        managerMembership.setWorkspace(workspace);
        managerMembership.setUser(managerUser);
        managerMembership.setRole(MembershipRole.MANAGER);
        managerMembership.setStatus(MembershipStatus.ACTIVE);

        User leadUser = new User();
        leadUser.setId(UUID.randomUUID());
        leadUser.setDisplayName("Bob Lead");
        leadUser.setEmail("bob@adept.test");

        leadMembership = new Membership();
        leadMembership.setId(UUID.randomUUID());
        leadMembership.setWorkspace(workspace);
        leadMembership.setUser(leadUser);
        leadMembership.setRole(MembershipRole.LEAD);
        leadMembership.setStatus(MembershipStatus.ACTIVE);

        project = new Project();
        project.setId(UUID.randomUUID());
        project.setWorkspace(workspace);
        project.setName("Payments Service");
        project.setDescription("Handles all checkout payments");
        project.setCreatedByMembership(managerMembership);

        principal = new AuthenticatedPrincipal(
            managerUser.getId(),
            managerMembership.getId(),
            workspace.getId(),
            MembershipRole.MANAGER,
            0
        );
    }

    @Test
    void getTeamReturnsManagerAndRepoLeads() {
        when(projectService.requireVisibleProject(principal, project.getId())).thenReturn(project);
        when(linkRepository.findActiveLeadMembershipsByProjectId(project.getId())).thenReturn(List.of(leadMembership));

        ProjectTeamResponse response = chatService.getTeam(principal, project.getId());

        assertThat(response.projectId()).isEqualTo(project.getId());
        assertThat(response.projectName()).isEqualTo("Payments Service");
        assertThat(response.manager()).isNotNull();
        assertThat(response.manager().displayName()).isEqualTo("Alice Manager");
        assertThat(response.members()).hasSize(2);
        assertThat(response.members().stream().map(m -> m.displayName()))
            .containsExactlyInAnyOrder("Alice Manager", "Bob Lead");
    }

    @Test
    void getMessagesReturnsOrderedDecryptedMessages() {
        when(projectService.requireVisibleProject(principal, project.getId())).thenReturn(project);

        ProjectChatMessage message1 = new ProjectChatMessage();
        message1.setId(UUID.randomUUID());
        message1.setWorkspace(workspace);
        message1.setProject(project);
        message1.setSenderMembership(managerMembership);
        message1.setContentEnc("aes_gcm_ciphertext_base64");
        message1.setEncryptionKeyVersion(1);
        message1.setCreatedAt(Instant.now());

        when(chatMessageRepository.findAllWithSenderByProjectId(workspace.getId(), project.getId()))
            .thenReturn(List.of(message1));
        when(encryptionService.decrypt("aes_gcm_ciphertext_base64", 1))
            .thenReturn("Welcome to Payments Service team chat!");

        List<ChatMessageResponse> messages = chatService.getMessages(principal, project.getId());

        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).content()).isEqualTo("Welcome to Payments Service team chat!");
        assertThat(messages.get(0).sender().displayName()).isEqualTo("Alice Manager");
    }

    @Test
    void sendMessageEncryptsSavesAndBroadcastsToWebSocketTopic() {
        when(projectService.requireVisibleProject(principal, project.getId())).thenReturn(project);
        when(projectService.requireCurrentMembership(principal)).thenReturn(managerMembership);
        when(encryptionService.encrypt("Deployment starting in 5 minutes"))
            .thenReturn(new IntegrationEncryptionService.EncryptedPayload("aes_gcm_ciphertext_base64", 1));

        ProjectChatMessage saved = new ProjectChatMessage();
        saved.setId(UUID.randomUUID());
        saved.setWorkspace(workspace);
        saved.setProject(project);
        saved.setSenderMembership(managerMembership);
        saved.setContentEnc("aes_gcm_ciphertext_base64");
        saved.setEncryptionKeyVersion(1);
        saved.setCreatedAt(Instant.now());

        when(chatMessageRepository.save(any(ProjectChatMessage.class))).thenReturn(saved);

        ChatMessageResponse response = chatService.sendMessage(principal, project.getId(), "Deployment starting in 5 minutes");

        assertThat(response.content()).isEqualTo("Deployment starting in 5 minutes");
        assertThat(response.sender().displayName()).isEqualTo("Alice Manager");

        ArgumentCaptor<ProjectChatMessage> captor = ArgumentCaptor.forClass(ProjectChatMessage.class);
        verify(chatMessageRepository).save(captor.capture());
        assertThat(captor.getValue().getContentEnc()).isEqualTo("aes_gcm_ciphertext_base64");
        assertThat(captor.getValue().getEncryptionKeyVersion()).isEqualTo(1);
        assertThat(captor.getValue().getProject()).isEqualTo(project);

        verify(messagingTemplate).convertAndSend(eq("/topic/projects/" + project.getId()), any(ChatMessageResponse.class));
    }
}
