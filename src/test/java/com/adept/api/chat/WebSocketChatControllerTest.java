package com.adept.api.chat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import com.adept.api.chat.dto.SendChatMessageRequest;
import com.adept.api.common.domain.MembershipRole;
import com.adept.api.security.AuthenticatedPrincipal;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class WebSocketChatControllerTest {

    @Mock
    private ChatService chatService;

    @InjectMocks
    private WebSocketChatController webSocketChatController;

    @Test
    void handleSendDelegatesToChatServiceWhenAuthenticated() {
        UUID projectId = UUID.randomUUID();
        AuthenticatedPrincipal principal = new AuthenticatedPrincipal(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            MembershipRole.LEAD,
            0
        );
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
            principal,
            null,
            List.of(new SimpleGrantedAuthority("ROLE_LEAD"))
        );

        SendChatMessageRequest request = new SendChatMessageRequest("Deploy approved!");
        webSocketChatController.handleSend(projectId, request, auth);

        verify(chatService).sendMessage(principal, projectId, "Deploy approved!");
    }
}
