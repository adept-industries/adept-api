package com.adept.api.deployment;

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
@Table(name = "deployments")
// Normalized deployment/workflow signal used by DORA calculations.
public class Deployment extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "workspace_id", nullable = false) private Workspace workspace;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "repository_id", nullable = false) private GitRepository repository;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) private DeploymentSource source;
    @Column(name = "external_deployment_id", nullable = false) private String externalDeploymentId;
    @Column(nullable = false) private String environment;
    @Column(name = "is_production", nullable = false) private boolean production;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 24) private DeploymentStatus status;
    @Column(name = "commit_sha", nullable = false, length = 64) private String commitSha;
    @Column(name = "started_at") private Instant startedAt;
    @Column(name = "finished_at") private Instant finishedAt;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "raw_data", nullable = false, columnDefinition = "jsonb") private Map<String,Object> rawData = Map.of();
}
