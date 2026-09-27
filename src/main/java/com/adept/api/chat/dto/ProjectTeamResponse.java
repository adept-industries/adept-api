package com.adept.api.chat.dto;

import java.util.List;
import java.util.UUID;

public record ProjectTeamResponse(
    UUID projectId,
    String projectName,
    String projectDescription,
    TeamMemberDto manager,
    List<TeamMemberDto> members
) {}
