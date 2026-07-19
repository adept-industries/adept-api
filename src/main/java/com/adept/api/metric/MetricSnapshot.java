package com.adept.api.metric;

import com.adept.api.common.domain.*;
import com.adept.api.integration.github.GitRepository;
import com.adept.api.workspace.Workspace;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

@Getter @Setter @NoArgsConstructor
@Entity
@Table(name = "metric_snapshots")
// Precalculated metric value for one period, granularity, and repository.
public class MetricSnapshot extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "workspace_id", nullable = false) private Workspace workspace;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "repository_id", nullable = false) private GitRepository repository;
    @Enumerated(EnumType.STRING) @Column(name = "metric_type", nullable = false, length = 64) private MetricType metricType;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private MetricGranularity granularity;
    @Column(name = "period_start", nullable = false) private Instant periodStart;
    @Column(name = "period_end", nullable = false) private Instant periodEnd;
    @Column(nullable = false, precision = 18, scale = 6) private BigDecimal value;
    @Column(nullable = false, length = 32) private String unit;
    @Column(name = "sample_size", nullable = false) private int sampleSize;
    @Column(name = "calculation_version", nullable = false, length = 64) private String calculationVersion;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") private Map<String,Object> dimensions = Map.of();
    @Column(name = "calculated_at", nullable = false) private Instant calculatedAt;
}
