package com.adept.api.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SendChatMessageRequest(
    @NotBlank(message = "Message content must not be blank")
    @Size(max = 4000, message = "Message content must not exceed 4000 characters")
    String content
) {}
