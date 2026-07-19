package com.adept.api.deployment;

import com.adept.api.common.domain.DeploymentLinkMethod;
import com.adept.api.pullrequest.PullRequest;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

@Embeddable
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @EqualsAndHashCode
// Composite key uniquely identifies one deployment-to-PR link.
class DeploymentPullRequestId implements Serializable {
    @Column(name = "deployment_id") private UUID deploymentId;
    @Column(name = "pull_request_id") private UUID pullRequestId;
}

@Getter @Setter @NoArgsConstructor
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "deployment_pull_requests")
// Join entity records which PR a deployment contains and how it was linked.
public class DeploymentPullRequest {
    @EmbeddedId private DeploymentPullRequestId id;
    @MapsId("deploymentId") @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "deployment_id", nullable = false) private Deployment deployment;
    @MapsId("pullRequestId") @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "pull_request_id", nullable = false) private PullRequest pullRequest;
    @Enumerated(EnumType.STRING) @Column(name = "link_method", nullable = false, length = 32) private DeploymentLinkMethod linkMethod;
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
}
