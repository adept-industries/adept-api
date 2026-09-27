package com.adept.api.chat.dto;

import java.time.Instant;
import java.util.UUID;

public record ChatMessageResponse(
    UUID id,
    UUID projectId,
    String content,
    Instant createdAt,
    ChatMessageSenderDto sender
) {}
