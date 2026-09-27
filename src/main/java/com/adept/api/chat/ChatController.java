package com.adept.api.chat;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.adept.api.chat.dto.ChatMessageResponse;
import com.adept.api.chat.dto.ProjectTeamResponse;
import com.adept.api.chat.dto.SendChatMessageRequest;
import com.adept.api.security.CurrentPrincipal;

import jakarta.validation.Valid;

@Validated
@RestController
@RequestMapping("/api/v1/projects/{projectId}")
public class ChatController {

    private final ChatService chatService;
    private final CurrentPrincipal currentPrincipal;

    public ChatController(ChatService chatService, CurrentPrincipal currentPrincipal) {
        this.chatService = chatService;
        this.currentPrincipal = currentPrincipal;
    }

    @GetMapping("/team")
    public ResponseEntity<ProjectTeamResponse> getTeam(@PathVariable UUID projectId) {
        return ResponseEntity.ok(chatService.getTeam(currentPrincipal.require(), projectId));
    }

    @GetMapping("/messages")
    public ResponseEntity<List<ChatMessageResponse>> getMessages(@PathVariable UUID projectId) {
        return ResponseEntity.ok(chatService.getMessages(currentPrincipal.require(), projectId));
    }

    @PostMapping("/messages")
    public ResponseEntity<ChatMessageResponse> sendMessage(
            @PathVariable UUID projectId,
            @Valid @RequestBody SendChatMessageRequest request) {
        ChatMessageResponse message = chatService.sendMessage(
            currentPrincipal.require(),
            projectId,
            request.content()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(message);
    }
}
