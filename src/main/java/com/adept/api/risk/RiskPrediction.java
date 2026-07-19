package com.adept.api.risk;

import com.adept.api.common.domain.*;
import com.adept.api.integration.github.GitRepository;
import com.adept.api.pullrequest.*;
import com.adept.api.workspace.Workspace;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Getter @Setter @NoArgsConstructor
@Entity
@Table(name = "risk_predictions")
// Versioned model result and user-readable top contributing factors.
public class RiskPrediction extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "workspace_id", nullable = false) private Workspace workspace;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "repository_id", nullable = false) private GitRepository repository;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "pull_request_id", nullable = false) private PullRequest pullRequest;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "feature_id") private PullRequestFeature feature;
    @Column(name = "model_name", nullable = false, length = 128) private String modelName;
    @Column(name = "model_version", nullable = false, length = 64) private String modelVersion;
    @Column(name = "risk_score", nullable = false, precision = 7, scale = 6) private BigDecimal riskScore;
    @Enumerated(EnumType.STRING) @Column(name = "risk_level", nullable = false, length = 16) private RiskLevel riskLevel;
    @Column(name = "threshold_used", precision = 7, scale = 6) private BigDecimal thresholdUsed;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "top_factors", nullable = false, columnDefinition = "jsonb") private List<Map<String,Object>> topFactors = List.of();
    @Column(name = "predicted_at", nullable = false) private Instant predictedAt = Instant.now();
}
