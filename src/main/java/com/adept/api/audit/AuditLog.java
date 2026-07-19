package com.adept.api.audit;

import com.adept.api.user.User;
import com.adept.api.workspace.*;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Getter @Setter @NoArgsConstructor
@Entity
@Table(name = "audit_logs")
// Append-only description of a security- or administration-relevant action.
public class AuditLog {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "workspace_id") private Workspace workspace;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "actor_user_id") private User actorUser;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "actor_membership_id") private Membership actorMembership;
    @Column(nullable = false, length = 128) private String action;
    @Column(name = "entity_type", nullable = false, length = 128) private String entityType;
    @Column(name = "entity_id") private UUID entityId;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") private Map<String,Object> metadata = Map.of();
    @Column(name = "ip_hash", length = 128) private String ipHash;
    @Column(name = "user_agent", columnDefinition = "text") private String userAgent;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
}
