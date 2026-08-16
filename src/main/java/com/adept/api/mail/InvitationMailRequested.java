package com.adept.api.mail;

import java.util.UUID;

public record InvitationMailRequested(
    UUID invitationId,
    String recipient,
    String workspaceName,
    String rawToken,
    String traceId
) {
    @Override
    public String toString() {
        return "InvitationMailRequested[invitationId=" + invitationId
            + ", recipient=<redacted>, workspaceName=" + workspaceName
            + ", rawToken=<redacted>, traceId=" + traceId + "]";
    }
}
