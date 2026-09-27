package com.adept.api.chat;

import java.security.Principal;
import java.util.UUID;

import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Controller;

import com.adept.api.chat.dto.SendChatMessageRequest;
import com.adept.api.security.AuthenticatedPrincipal;

import jakarta.validation.Valid;

@Controller
public class WebSocketChatController {

    private final ChatService chatService;

    public WebSocketChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @MessageMapping("/projects/{projectId}/chat.send")
    public void handleSend(
            @DestinationVariable UUID projectId,
            @Valid @Payload SendChatMessageRequest request,
            Principal principal) {
        if (principal instanceof UsernamePasswordAuthenticationToken auth
                && auth.getPrincipal() instanceof AuthenticatedPrincipal authenticatedPrincipal) {
            chatService.sendMessage(authenticatedPrincipal, projectId, request.content());
        }
    }
}
