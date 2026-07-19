package com.adept.api.notification;

import com.adept.api.alert.AlertRule;
import com.adept.api.common.domain.*;
import com.adept.api.integration.github.GitRepository;
import com.adept.api.workspace.Workspace;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.Map;

@Getter @Setter @NoArgsConstructor
@Entity
@Table(name = "notification_deliveries")
// Durable send attempt for a triggered alert event.
public class NotificationDelivery extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "workspace_id", nullable = false) private Workspace workspace;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "repository_id", nullable = false) private GitRepository repository;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "alert_rule_id", nullable = false) private AlertRule alertRule;
    @Column(name = "event_key", nullable = false) private String eventKey;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private NotificationChannel channel;
    @Column(nullable = false, length = 320) private String destination;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private NotificationStatus status = NotificationStatus.PENDING;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") private Map<String,Object> payload = Map.of();
    @Column(nullable = false) private int attempts;
    @Column(name = "last_error", columnDefinition = "text") private String lastError;
    @Column(name = "sent_at") private Instant sentAt;
}
