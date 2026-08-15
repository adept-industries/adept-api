package com.adept.api.integration.github.dto;

public record LeadCandidateResponse(
    String githubUserId,
    String login,
    String avatarUrl,
    String permission,
    String publicEmail
) {
}
