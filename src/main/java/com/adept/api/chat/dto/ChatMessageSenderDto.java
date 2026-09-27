package com.adept.api.chat.dto;

import java.util.UUID;
import com.adept.api.common.domain.MembershipRole;

public record ChatMessageSenderDto(
    UUID membershipId,
    UUID userId,
    String displayName,
    String email,
    String avatarUrl,
    MembershipRole role
) {}
