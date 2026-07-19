package com.adept.api.webhook;

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
@Table(name = "raw_webhook_events",
       uniqueConstraints = @UniqueConstraint(columnNames = {"source", "delivery_id"}))
// Preserves the received provider event before background normalization.
public class RawWebhookEvent extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "workspace_id") private Workspace workspace;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "repository_id") private GitRepository repository;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16) private WebhookSource source;

    @Column(name = "delivery_id", nullable = false) private String deliveryId;
    @Column(name = "event_type", nullable = false, length = 128) private String eventType;
    @Column(length = 128) private String action;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> headers = Map.of();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private WebhookStatus status = WebhookStatus.RECEIVED;

    @Column(name = "signature_valid", nullable = false) private boolean signatureValid;
    @Column(name = "attempt_count", nullable = false) private int attemptCount;
    @Column(name = "last_error", columnDefinition = "text") private String lastError;
    @Column(name = "received_at", nullable = false) private Instant receivedAt = Instant.now();
    @Column(name = "processing_started_at") private Instant processingStartedAt;
    @Column(name = "processed_at") private Instant processedAt;
}
