package com.adept.api.job;

import com.adept.api.common.domain.*;
import com.adept.api.integration.github.GitRepository;
import com.adept.api.webhook.RawWebhookEvent;
import com.adept.api.workspace.Workspace;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.Map;

@Getter @Setter @NoArgsConstructor
@Entity
@Table(name = "processing_jobs")
// Durable queue row claimed by one worker at a time.
public class ProcessingJob extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "workspace_id") private Workspace workspace;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "repository_id") private GitRepository repository;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "raw_event_id") private RawWebhookEvent rawEvent;

    @Enumerated(EnumType.STRING)
    @Column(name = "job_type", nullable = false, length = 64)
    private ProcessingJobType jobType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload = Map.of();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ProcessingJobStatus status = ProcessingJobStatus.PENDING;

    @Column(nullable = false) private int priority = 100;
    @Column(nullable = false) private int attempts;
    @Column(name = "max_attempts", nullable = false) private int maxAttempts = 8;
    @Column(name = "available_at", nullable = false) private Instant availableAt;
    @Column(name = "locked_at") private Instant lockedAt;
    @Column(name = "locked_by", length = 128) private String lockedBy;
    @Column(name = "last_error", columnDefinition = "text") private String lastError;
    @Column(name = "finished_at") private Instant finishedAt;
}
