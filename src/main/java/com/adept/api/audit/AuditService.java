package com.adept.api.audit;

import java.util.Map;

import org.springframework.stereotype.Service;

import com.adept.api.crypto.TokenHasher;
import com.adept.api.user.User;
import com.adept.api.workspace.Membership;
import com.adept.api.workspace.Workspace;

@Service
public class AuditService {

    private static final int MAX_USER_AGENT_LENGTH = 512;

    private final AuditLogRepository auditLogRepository;
    private final TokenHasher tokenHasher;

    public AuditService(AuditLogRepository auditLogRepository, TokenHasher tokenHasher) {
        this.auditLogRepository = auditLogRepository;
        this.tokenHasher = tokenHasher;
    }

    public void record(
            AuditAction action,
            User actorUser,
            Membership actorMembership,
            Workspace workspace,
            String entityType,
            java.util.UUID entityId,
            Map<String, Object> metadata,
            String ipAddress,
            String userAgent) {
        AuditLog log = new AuditLog();
        log.setAction(action.name());
        log.setActorUser(actorUser);
        log.setActorMembership(actorMembership);
        log.setWorkspace(workspace);
        log.setEntityType(entityType);
        log.setEntityId(entityId);
        log.setMetadata(metadata == null ? Map.of() : metadata);
        if (ipAddress != null && !ipAddress.isBlank()) {
            log.setIpHash(tokenHasher.hashAuditIp(ipAddress));
        }
        String safeAgent = safeUserAgent(userAgent);
        if (!safeAgent.isBlank()) {
            log.setUserAgent(tokenHasher.hashUserAgent(safeAgent));
        }
        auditLogRepository.save(log);
    }

    public void record(
            AuditAction action,
            User actorUser,
            Membership actorMembership,
            Workspace workspace,
            String entityType,
            java.util.UUID entityId,
            Map<String, Object> metadata) {
        record(action, actorUser, actorMembership, workspace, entityType, entityId, metadata, null, null);
    }

    private static String safeUserAgent(String value) {
        if (value == null) {
            return "";
        }
        String stripped = value.replaceAll("\\p{Cntrl}", "").trim();
        return stripped.length() <= MAX_USER_AGENT_LENGTH
            ? stripped
            : stripped.substring(0, MAX_USER_AGENT_LENGTH);
    }
}
