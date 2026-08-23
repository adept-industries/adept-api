package com.adept.api.metric;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.adept.api.common.domain.MembershipRole;
import com.adept.api.common.domain.MetricGranularity;
import com.adept.api.common.domain.MetricType;
import com.adept.api.common.error.NotFoundException;
import com.adept.api.common.error.ProblemCode;
import com.adept.api.integration.github.GitRepository;
import com.adept.api.integration.github.GitRepositoryRepository;
import com.adept.api.metric.dto.DoraMetricsSeriesResponse;
import com.adept.api.metric.dto.DoraMetricsSummaryResponse;
import com.adept.api.metric.dto.MetricSeriesItemDto;
import com.adept.api.metric.dto.MetricSummaryDto;
import com.adept.api.project.ProjectRepository;
import com.adept.api.project.ProjectRepositoryLinkRepository;
import com.adept.api.security.AuthenticatedPrincipal;
import com.adept.api.security.RepositoryScopeService;

@Service
@Transactional(readOnly = true)
public class MetricService {

    private final MetricSnapshotRepository metricSnapshotRepository;
    private final GitRepositoryRepository gitRepositoryRepository;
    private final ProjectRepository projectRepository;
    private final ProjectRepositoryLinkRepository projectRepositoryLinkRepository;
    private final RepositoryScopeService repositoryScopeService;

    public MetricService(
            MetricSnapshotRepository metricSnapshotRepository,
            GitRepositoryRepository gitRepositoryRepository,
            ProjectRepository projectRepository,
            ProjectRepositoryLinkRepository projectRepositoryLinkRepository,
            RepositoryScopeService repositoryScopeService) {
        this.metricSnapshotRepository = metricSnapshotRepository;
        this.gitRepositoryRepository = gitRepositoryRepository;
        this.projectRepository = projectRepository;
        this.projectRepositoryLinkRepository = projectRepositoryLinkRepository;
        this.repositoryScopeService = repositoryScopeService;
    }

    public DoraMetricsSummaryResponse getSummary(
            AuthenticatedPrincipal principal,
            UUID projectId,
            UUID repositoryId,
            Instant from,
            Instant to) {
        List<UUID> repoIds = resolveAccessibleRepositoryIds(principal, projectId, repositoryId);

        Instant periodEnd = to != null ? to : Instant.now();
        Instant periodStart = from != null ? from : periodEnd.minus(30, ChronoUnit.DAYS);

        if (repoIds.isEmpty()) {
            return new DoraMetricsSummaryResponse(
                principal.workspaceId(),
                projectId,
                repositoryId,
                0,
                periodStart,
                periodEnd,
                MetricSummaryDto.empty("deployments/week"),
                MetricSummaryDto.empty("hours"),
                MetricSummaryDto.empty("hours"),
                MetricSummaryDto.empty("percent"),
                Instant.now()
            );
        }

        List<MetricSnapshot> snapshots = metricSnapshotRepository.findSnapshots(
            principal.workspaceId(),
            repoIds,
            MetricGranularity.DAY,
            periodStart,
            periodEnd
        );

        MetricSummaryDto dfSummary = aggregateDeploymentFrequency(snapshots, periodStart, periodEnd);
        MetricSummaryDto cltSummary = aggregateChangeLeadTime(snapshots);
        MetricSummaryDto mttrSummary = aggregateRecoveryTime(snapshots);
        MetricSummaryDto cfrSummary = aggregateChangeFailureRate(snapshots);

        return new DoraMetricsSummaryResponse(
            principal.workspaceId(),
            projectId,
            repositoryId,
            repoIds.size(),
            periodStart,
            periodEnd,
            dfSummary,
            cltSummary,
            mttrSummary,
            cfrSummary,
            Instant.now()
        );
    }

    public DoraMetricsSeriesResponse getSeries(
            AuthenticatedPrincipal principal,
            UUID projectId,
            UUID repositoryId,
            MetricType metricType,
            MetricGranularity granularity,
            Instant from,
            Instant to) {
        List<UUID> repoIds = resolveAccessibleRepositoryIds(principal, projectId, repositoryId);

        MetricGranularity effectiveGranularity = granularity != null ? granularity : MetricGranularity.DAY;
        Instant periodEnd = to != null ? to : Instant.now();
        Instant periodStart = from != null ? from : periodEnd.minus(30, ChronoUnit.DAYS);

        if (repoIds.isEmpty()) {
            return new DoraMetricsSeriesResponse(
                principal.workspaceId(),
                projectId,
                repositoryId,
                0,
                effectiveGranularity,
                List.of()
            );
        }

        List<MetricSnapshot> snapshots;
        if (metricType != null) {
            snapshots = metricSnapshotRepository.findSnapshotsByMetricType(
                principal.workspaceId(),
                repoIds,
                metricType,
                effectiveGranularity,
                periodStart,
                periodEnd
            );
        } else {
            snapshots = metricSnapshotRepository.findSnapshots(
                principal.workspaceId(),
                repoIds,
                effectiveGranularity,
                periodStart,
                periodEnd
            );
        }

        List<MetricSeriesItemDto> seriesItems = aggregateSeries(snapshots);

        return new DoraMetricsSeriesResponse(
            principal.workspaceId(),
            projectId,
            repositoryId,
            repoIds.size(),
            effectiveGranularity,
            seriesItems
        );
    }

    private List<UUID> resolveAccessibleRepositoryIds(
            AuthenticatedPrincipal principal,
            UUID projectId,
            UUID repositoryId) {
        if (repositoryId != null) {
            repositoryScopeService.requireReadableRepository(principal, repositoryId);
            return List.of(repositoryId);
        }

        if (projectId != null) {
            projectRepository.findByIdAndWorkspaceId(projectId, principal.workspaceId())
                .orElseThrow(() -> new NotFoundException(ProblemCode.PROJECT_NOT_FOUND));

            if (principal.role() == MembershipRole.MANAGER) {
                return projectRepositoryLinkRepository.findAllWithRepositoryByProjectId(projectId)
                    .stream()
                    .map(link -> link.getRepository().getId())
                    .toList();
            } else {
                return projectRepositoryLinkRepository.findAllReadableByLead(projectId, principal.membershipId())
                    .stream()
                    .map(link -> link.getRepository().getId())
                    .toList();
            }
        }

        if (principal.role() == MembershipRole.MANAGER) {
            return gitRepositoryRepository.findAllByWorkspaceId(principal.workspaceId())
                .stream()
                .filter(r -> r.isTrackingEnabled() && !r.isArchived())
                .map(GitRepository::getId)
                .toList();
        } else {
            return gitRepositoryRepository.findAllLeadReadableRepositories(
                principal.workspaceId(),
                principal.membershipId()
            ).stream().map(GitRepository::getId).toList();
        }
    }

    private MetricSummaryDto aggregateDeploymentFrequency(
            List<MetricSnapshot> snapshots,
            Instant periodStart,
            Instant periodEnd) {
        List<MetricSnapshot> dfSnaps = snapshots.stream()
            .filter(s -> s.getMetricType() == MetricType.DEPLOYMENT_FREQUENCY)
            .toList();

        double totalDeployments = dfSnaps.stream()
            .mapToDouble(s -> s.getValue().doubleValue())
            .sum();

        long days = Math.max(1, ChronoUnit.DAYS.between(periodStart, periodEnd));
        double weeks = Math.max(1.0, days / 7.0);
        double deploysPerWeek = totalDeployments / weeks;

        BigDecimal roundedVal = BigDecimal.valueOf(deploysPerWeek).setScale(2, RoundingMode.HALF_UP);
        MetricRating rating = MetricRating.rateDeploymentFrequency(deploysPerWeek);

        Map<String, Object> dims = Map.of(
            "total_deployments", (int) totalDeployments,
            "period_days", days
        );

        return new MetricSummaryDto(
            roundedVal,
            "deployments/week",
            (int) totalDeployments,
            rating,
            dims
        );
    }

    private MetricSummaryDto aggregateChangeLeadTime(List<MetricSnapshot> snapshots) {
        List<MetricSnapshot> cltSnaps = snapshots.stream()
            .filter(s -> s.getMetricType() == MetricType.CHANGE_LEAD_TIME_HOURS && s.getSampleSize() > 0)
            .toList();

        if (cltSnaps.isEmpty()) {
            return MetricSummaryDto.empty("hours");
        }

        int totalSamples = cltSnaps.stream().mapToInt(MetricSnapshot::getSampleSize).sum();
        double weightedSum = cltSnaps.stream()
            .mapToDouble(s -> s.getValue().doubleValue() * s.getSampleSize())
            .sum();

        double avgHours = totalSamples > 0 ? weightedSum / totalSamples : 0.0;
        BigDecimal roundedVal = BigDecimal.valueOf(avgHours).setScale(2, RoundingMode.HALF_UP);
        MetricRating rating = MetricRating.rateChangeLeadTime(avgHours, totalSamples);

        return new MetricSummaryDto(
            roundedVal,
            "hours",
            totalSamples,
            rating,
            Map.of("p50", roundedVal.doubleValue())
        );
    }

    private MetricSummaryDto aggregateRecoveryTime(List<MetricSnapshot> snapshots) {
        List<MetricSnapshot> recSnaps = snapshots.stream()
            .filter(s -> s.getMetricType() == MetricType.FAILED_DEPLOYMENT_RECOVERY_TIME_HOURS && s.getSampleSize() > 0)
            .toList();

        if (recSnaps.isEmpty()) {
            return MetricSummaryDto.empty("hours");
        }

        int totalSamples = recSnaps.stream().mapToInt(MetricSnapshot::getSampleSize).sum();
        double weightedSum = recSnaps.stream()
            .mapToDouble(s -> s.getValue().doubleValue() * s.getSampleSize())
            .sum();

        double avgHours = totalSamples > 0 ? weightedSum / totalSamples : 0.0;
        BigDecimal roundedVal = BigDecimal.valueOf(avgHours).setScale(2, RoundingMode.HALF_UP);
        MetricRating rating = MetricRating.rateRecoveryTime(avgHours, totalSamples);

        return new MetricSummaryDto(
            roundedVal,
            "hours",
            totalSamples,
            rating,
            Map.of("p50", roundedVal.doubleValue())
        );
    }

    private MetricSummaryDto aggregateChangeFailureRate(List<MetricSnapshot> snapshots) {
        List<MetricSnapshot> cfrSnaps = snapshots.stream()
            .filter(s -> s.getMetricType() == MetricType.CHANGE_FAILURE_RATE_PERCENT && s.getSampleSize() > 0)
            .toList();

        if (cfrSnaps.isEmpty()) {
            return MetricSummaryDto.empty("percent");
        }

        int totalDeployments = 0;
        double failedDeployments = 0;

        for (MetricSnapshot s : cfrSnaps) {
            int samples = s.getSampleSize();
            totalDeployments += samples;
            failedDeployments += (s.getValue().doubleValue() / 100.0) * samples;
        }

        if (totalDeployments == 0) {
            return MetricSummaryDto.empty("percent");
        }

        double rate = (failedDeployments / totalDeployments) * 100.0;
        BigDecimal roundedVal = BigDecimal.valueOf(rate).setScale(2, RoundingMode.HALF_UP);
        MetricRating rating = MetricRating.rateChangeFailureRate(rate, totalDeployments);

        return new MetricSummaryDto(
            roundedVal,
            "percent",
            totalDeployments,
            rating,
            Map.of(
                "total_deployments", totalDeployments,
                "failed_deployments", (int) Math.round(failedDeployments)
            )
        );
    }

    private List<MetricSeriesItemDto> aggregateSeries(List<MetricSnapshot> snapshots) {
        // Group by (metricType, periodStart, periodEnd)
        record BucketKey(MetricType metricType, Instant periodStart, Instant periodEnd, String unit) {}

        Map<BucketKey, List<MetricSnapshot>> groups = new LinkedHashMap<>();
        for (MetricSnapshot s : snapshots) {
            BucketKey key = new BucketKey(s.getMetricType(), s.getPeriodStart(), s.getPeriodEnd(), s.getUnit());
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(s);
        }

        List<MetricSeriesItemDto> result = new ArrayList<>();

        for (Map.Entry<BucketKey, List<MetricSnapshot>> entry : groups.entrySet()) {
            BucketKey key = entry.getKey();
            List<MetricSnapshot> snaps = entry.getValue();

            BigDecimal value;
            int sampleSize;
            Map<String, Object> dims = new HashMap<>();

            if (key.metricType() == MetricType.DEPLOYMENT_FREQUENCY) {
                double total = snaps.stream().mapToDouble(s -> s.getValue().doubleValue()).sum();
                value = BigDecimal.valueOf(total).setScale(2, RoundingMode.HALF_UP);
                sampleSize = (int) total;
            } else if (key.metricType() == MetricType.CHANGE_FAILURE_RATE_PERCENT) {
                int totalDeploys = 0;
                double failedDeploys = 0;
                for (MetricSnapshot s : snaps) {
                    int n = s.getSampleSize();
                    totalDeploys += n;
                    failedDeploys += (s.getValue().doubleValue() / 100.0) * n;
                }
                sampleSize = totalDeploys;
                double rate = totalDeploys > 0 ? (failedDeploys / totalDeploys) * 100.0 : 0.0;
                value = BigDecimal.valueOf(rate).setScale(2, RoundingMode.HALF_UP);
                dims.put("total_deployments", totalDeploys);
                dims.put("failed_deployments", (int) Math.round(failedDeploys));
            } else {
                // Change Lead Time or Recovery Time: weighted average
                int totalSamples = snaps.stream().mapToInt(MetricSnapshot::getSampleSize).sum();
                double weightedSum = snaps.stream()
                    .mapToDouble(s -> s.getValue().doubleValue() * s.getSampleSize())
                    .sum();
                sampleSize = totalSamples;
                double avg = totalSamples > 0 ? weightedSum / totalSamples : 0.0;
                value = BigDecimal.valueOf(avg).setScale(2, RoundingMode.HALF_UP);
            }

            result.add(new MetricSeriesItemDto(
                key.metricType(),
                key.periodStart(),
                key.periodEnd(),
                value,
                key.unit(),
                sampleSize,
                dims
            ));
        }

        return result;
    }
}
