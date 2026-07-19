package com.adept.api.alert;

import com.adept.api.common.domain.*;
import com.adept.api.integration.github.GitRepository;
import com.adept.api.workspace.*;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;

@Getter @Setter @NoArgsConstructor
@Entity
@Table(name = "alert_rules")
// User-defined threshold comparison for a repository metric/risk value.
public class AlertRule extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "workspace_id", nullable = false) private Workspace workspace;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "repository_id", nullable = false) private GitRepository repository;
    // Set when created; may become null later if that membership is removed.
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "created_by_membership_id") private Membership createdBy;
    @Column(nullable = false, length = 160) private String name;
    @Enumerated(EnumType.STRING) @Column(name = "metric_type", nullable = false, length = 64) private AlertMetricType metricType;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 8) private AlertComparator comparator;
    @Column(name = "threshold_value", nullable = false, precision = 18, scale = 6) private BigDecimal thresholdValue;
    @Column(name = "evaluation_window_minutes", nullable = false) private int evaluationWindowMinutes = 1440;
    @Column(name = "cooldown_minutes", nullable = false) private int cooldownMinutes = 1440;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private NotificationChannel channel = NotificationChannel.EMAIL;
    @Column(nullable = false, length = 320) private String destination;
    @Column(nullable = false) private boolean enabled = true;
    @Column(name = "last_triggered_at") private Instant lastTriggeredAt;
}
