package com.adept.api.metric;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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
import com.adept.api.common.error.ApiException;
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
import com.adept.api.workspace.WorkspaceRepository;

@Service
@Transactional(readOnly = true)
public class MetricService {

    static final String CALCULATION_VERSION = "dora-v2";
    private static final Duration STALE_AFTER = Duration.ofHours(24);

    private final MetricSnapshotRepository metricSnapshotRepository;
    private final GitRepositoryRepository gitRepositoryRepository;
    private final ProjectRepository projectRepository;
    private final ProjectRepositoryLinkRepository projectRepositoryLinkRepository;
    private final RepositoryScopeService repositoryScopeService;
    private final WorkspaceRepository workspaceRepository;

    public MetricService(
            MetricSnapshotRepository metricSnapshotRepository,
            GitRepositoryRepository gitRepositoryRepository,
            ProjectRepository projectRepository,
            ProjectRepositoryLinkRepository projectRepositoryLinkRepository,
            RepositoryScopeService repositoryScopeService,
            WorkspaceRepository workspaceRepository) {
        this.metricSnapshotRepository = metricSnapshotRepository;
        this.gitRepositoryRepository = gitRepositoryRepository;
        this.projectRepository = projectRepository;
        this.projectRepositoryLinkRepository = projectRepositoryLinkRepository;
        this.repositoryScopeService = repositoryScopeService;
        this.workspaceRepository = workspaceRepository;
    }

    public DoraMetricsSummaryResponse getSummary(
            AuthenticatedPrincipal principal,
            UUID projectId,
            UUID repositoryId,
            Instant from,
            Instant to) {
        MetricRange range = validateRange(projectId, repositoryId, from, to);
        List<UUID> repositoryIds = resolveAccessibleRepositoryIds(principal, projectId, repositoryId);
        String timezone = workspaceTimezone(principal);

        List<MetricSnapshot> snapshots = repositoryIds.isEmpty()
            ? List.of()
            : metricSnapshotRepository.findSnapshots(
                principal.workspaceId(),
                repositoryIds,
                MetricGranularity.DAY,
                CALCULATION_VERSION,
                range.start(),
                range.end()
            );

        Instant calculatedAt = latestCalculation(snapshots);
        return new DoraMetricsSummaryResponse(
            principal.workspaceId(),
            projectId,
            repositoryId,
            repositoryIds.size(),
            range.start(),
            range.end(),
            timezone,
            CALCULATION_VERSION,
            aggregateDeploymentFrequency(snapshots, range),
            aggregateDuration(snapshots, MetricType.CHANGE_LEAD_TIME_HOURS, range),
            aggregateDuration(
                snapshots,
                MetricType.FAILED_DEPLOYMENT_RECOVERY_TIME_HOURS,
                range
            ),
            aggregateChangeFailureRate(snapshots, range),
            calculatedAt,
            isStale(calculatedAt)
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
        MetricRange range = validateRange(projectId, repositoryId, from, to);
        List<UUID> repositoryIds = resolveAccessibleRepositoryIds(principal, projectId, repositoryId);
        MetricGranularity effectiveGranularity = granularity != null ? granularity : MetricGranularity.DAY;
        String timezone = workspaceTimezone(principal);

        List<MetricSnapshot> snapshots;
        if (repositoryIds.isEmpty()) {
            snapshots = List.of();
        } else if (metricType != null) {
            snapshots = metricSnapshotRepository.findSnapshotsByMetricType(
                principal.workspaceId(),
                repositoryIds,
                metricType,
                effectiveGranularity,
                CALCULATION_VERSION,
                range.start(),
                range.end()
            );
        } else {
            snapshots = metricSnapshotRepository.findSnapshots(
                principal.workspaceId(),
                repositoryIds,
                effectiveGranularity,
                CALCULATION_VERSION,
                range.start(),
                range.end()
            );
        }

        Instant calculatedAt = latestCalculation(snapshots);
        return new DoraMetricsSeriesResponse(
            principal.workspaceId(),
            projectId,
            repositoryId,
            repositoryIds.size(),
            range.start(),
            range.end(),
            timezone,
            effectiveGranularity,
            CALCULATION_VERSION,
            calculatedAt,
            isStale(calculatedAt),
            aggregateSeries(snapshots, range)
        );
    }

    private MetricRange validateRange(
            UUID projectId,
            UUID repositoryId,
            Instant from,
            Instant to) {
        if (projectId != null && repositoryId != null) {
            throw new ApiException(
                ProblemCode.VALIDATION_FAILED,
                "projectId and repositoryId cannot be supplied together."
            );
        }
        Instant end = to != null ? to : Instant.now();
        Instant start = from != null ? from : end.minus(30, ChronoUnit.DAYS);
        if (!start.isBefore(end)) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "from must be before to.");
        }
        if (Duration.between(start, end).compareTo(Duration.ofDays(366)) > 0) {
            throw new ApiException(
                ProblemCode.VALIDATION_FAILED,
                "Metric ranges cannot exceed 366 days."
            );
        }
        return new MetricRange(start, end);
    }

    private String workspaceTimezone(AuthenticatedPrincipal principal) {
        return workspaceRepository.findById(principal.workspaceId())
            .orElseThrow(() -> new NotFoundException(ProblemCode.WORKSPACE_NOT_FOUND))
            .getTimezone();
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
                    .map(link -> link.getRepository())
                    .filter(MetricService::isMetricRepository)
                    .map(GitRepository::getId)
                    .distinct()
                    .toList();
            }
            return projectRepositoryLinkRepository.findAllReadableByLead(
                    projectId,
                    principal.membershipId()
                )
                .stream()
                .map(link -> link.getRepository())
                .filter(MetricService::isMetricRepository)
                .map(GitRepository::getId)
                .distinct()
                .toList();
        }

        if (principal.role() == MembershipRole.MANAGER) {
            return gitRepositoryRepository.findAllByWorkspaceId(principal.workspaceId())
                .stream()
                .filter(MetricService::isMetricRepository)
                .map(GitRepository::getId)
                .toList();
        }
        return gitRepositoryRepository.findAllLeadReadableRepositories(
                principal.workspaceId(),
                principal.membershipId()
            )
            .stream()
            .filter(MetricService::isMetricRepository)
            .map(GitRepository::getId)
            .toList();
    }

    private static boolean isMetricRepository(GitRepository repository) {
        return repository.isTrackingEnabled() && !repository.isArchived();
    }

    private MetricSummaryDto aggregateDeploymentFrequency(
            List<MetricSnapshot> snapshots,
            MetricRange range) {
        int deploymentCount = observations(
            snapshots,
            MetricType.DEPLOYMENT_FREQUENCY,
            range
        ).size();
        long periodDays = Math.max(1, Duration.between(range.start(), range.end()).toDays());
        if (deploymentCount == 0) {
            return new MetricSummaryDto(
                BigDecimal.ZERO,
                "deployments/week",
                0,
                MetricRating.UNKNOWN,
                Map.of("total_deployments", 0, "period_days", periodDays)
            );
        }
        double weeks = Duration.between(range.start(), range.end()).toMillis()
            / (double) Duration.ofDays(7).toMillis();
        double deploymentsPerWeek = weeks > 0 ? deploymentCount / weeks : 0.0;

        return new MetricSummaryDto(
            decimal(deploymentsPerWeek),
            "deployments/week",
            deploymentCount,
            MetricRating.rateDeploymentFrequency(deploymentsPerWeek),
            Map.of("total_deployments", deploymentCount, "period_days", periodDays)
        );
    }

    private MetricSummaryDto aggregateDuration(
            List<MetricSnapshot> snapshots,
            MetricType metricType,
            MetricRange range) {
        List<Double> values = observations(snapshots, metricType, range)
            .stream()
            .map(Observation::value)
            .sorted()
            .toList();
        if (values.isEmpty()) {
            return MetricSummaryDto.empty("hours");
        }

        Map<String, Object> dimensions = percentileDimensions(values);
        double median = (double) dimensions.get("p50");
        MetricRating rating = metricType == MetricType.CHANGE_LEAD_TIME_HOURS
            ? MetricRating.rateChangeLeadTime(median, values.size())
            : MetricRating.rateRecoveryTime(median, values.size());
        return new MetricSummaryDto(
            decimal(median),
            "hours",
            values.size(),
            rating,
            dimensions
        );
    }

    private MetricSummaryDto aggregateChangeFailureRate(
            List<MetricSnapshot> snapshots,
            MetricRange range) {
        List<Observation> deployments = observations(
            snapshots,
            MetricType.CHANGE_FAILURE_RATE_PERCENT,
            range
        );
        if (deployments.isEmpty()) {
            return MetricSummaryDto.empty("percent");
        }
        long failed = deployments.stream().filter(observation -> observation.value() >= 0.5).count();
        double rate = failed * 100.0 / deployments.size();
        return new MetricSummaryDto(
            decimal(rate),
            "percent",
            deployments.size(),
            MetricRating.rateChangeFailureRate(rate, deployments.size()),
            Map.of(
                "total_deployments", deployments.size(),
                "failed_deployments", failed
            )
        );
    }

    private List<MetricSeriesItemDto> aggregateSeries(
            List<MetricSnapshot> snapshots,
            MetricRange requestedRange) {
        record BucketKey(MetricType type, Instant start, Instant end, String unit) {}
        Map<BucketKey, List<MetricSnapshot>> buckets = new LinkedHashMap<>();
        snapshots.stream()
            .sorted((left, right) -> left.getPeriodStart().compareTo(right.getPeriodStart()))
            .forEach(snapshot -> {
                BucketKey key = new BucketKey(
                    snapshot.getMetricType(),
                    snapshot.getPeriodStart(),
                    snapshot.getPeriodEnd(),
                    snapshot.getUnit()
                );
                buckets.computeIfAbsent(key, ignored -> new ArrayList<>()).add(snapshot);
            });

        List<MetricSeriesItemDto> result = new ArrayList<>();
        for (Map.Entry<BucketKey, List<MetricSnapshot>> entry : buckets.entrySet()) {
            BucketKey key = entry.getKey();
            MetricRange bucketRange = new MetricRange(
                key.start().isAfter(requestedRange.start()) ? key.start() : requestedRange.start(),
                key.end().isBefore(requestedRange.end()) ? key.end() : requestedRange.end()
            );
            List<Observation> values = observations(entry.getValue(), key.type(), bucketRange);
            Map<String, Object> dimensions = new HashMap<>();
            double value;

            if (key.type() == MetricType.DEPLOYMENT_FREQUENCY) {
                value = values.size();
                dimensions.put("total_deployments", values.size());
            } else if (key.type() == MetricType.CHANGE_FAILURE_RATE_PERCENT) {
                long failed = values.stream().filter(observation -> observation.value() >= 0.5).count();
                value = values.isEmpty() ? 0.0 : failed * 100.0 / values.size();
                dimensions.put("total_deployments", values.size());
                dimensions.put("failed_deployments", failed);
            } else {
                List<Double> durations = values.stream().map(Observation::value).sorted().toList();
                dimensions.putAll(percentileDimensions(durations));
                value = durations.isEmpty() ? 0.0 : percentile(durations, 0.5);
            }

            result.add(new MetricSeriesItemDto(
                key.type(),
                key.start(),
                key.end(),
                decimal(value),
                key.unit(),
                values.size(),
                dimensions
            ));
        }
        return result;
    }

    private List<Observation> observations(
            List<MetricSnapshot> snapshots,
            MetricType metricType,
            MetricRange range) {
        Map<String, Observation> unique = new LinkedHashMap<>();
        for (MetricSnapshot snapshot : snapshots) {
            if (snapshot.getMetricType() != metricType || snapshot.getDimensions() == null) {
                continue;
            }
            Object rawObservations = snapshot.getDimensions().get("observations");
            if (!(rawObservations instanceof List<?> list)) {
                continue;
            }
            int index = 0;
            for (Object rawObservation : list) {
                if (!(rawObservation instanceof Map<?, ?> map)) {
                    continue;
                }
                try {
                    Instant at = Instant.parse(String.valueOf(map.get("at")));
                    if (!range.contains(at)) {
                        continue;
                    }
                    double value = Double.parseDouble(String.valueOf(map.get("value")));
                    Object rawKey = map.get("key");
                    String key = rawKey == null || String.valueOf(rawKey).isBlank()
                        ? snapshot.getRepository().getId() + ":" + snapshot.getPeriodStart() + ":" + index
                        : String.valueOf(rawKey);
                    unique.putIfAbsent(key, new Observation(key, at, value));
                } catch (RuntimeException ignored) {
                    // Malformed observations are excluded instead of corrupting an aggregate.
                }
                index++;
            }
        }
        return List.copyOf(unique.values());
    }

    private static Map<String, Object> percentileDimensions(List<Double> sortedValues) {
        if (sortedValues.isEmpty()) {
            return Map.of();
        }
        double mean = sortedValues.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        return Map.of(
            "mean", decimal(mean).doubleValue(),
            "p50", decimal(percentile(sortedValues, 0.50)).doubleValue(),
            "p75", decimal(percentile(sortedValues, 0.75)).doubleValue(),
            "p90", decimal(percentile(sortedValues, 0.90)).doubleValue()
        );
    }

    private static double percentile(List<Double> sortedValues, double percentile) {
        if (sortedValues.size() == 1) {
            return sortedValues.getFirst();
        }
        double rank = percentile * (sortedValues.size() - 1);
        int lower = (int) Math.floor(rank);
        int upper = Math.min(lower + 1, sortedValues.size() - 1);
        double weight = rank - lower;
        return sortedValues.get(lower) * (1.0 - weight) + sortedValues.get(upper) * weight;
    }

    private static BigDecimal decimal(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }

    private static Instant latestCalculation(List<MetricSnapshot> snapshots) {
        return snapshots.stream()
            .map(MetricSnapshot::getCalculatedAt)
            .filter(value -> value != null)
            .max(Instant::compareTo)
            .orElse(null);
    }

    private static boolean isStale(Instant calculatedAt) {
        return calculatedAt == null || calculatedAt.isBefore(Instant.now().minus(STALE_AFTER));
    }

    private record Observation(String key, Instant at, double value) {}

    private record MetricRange(Instant start, Instant end) {
        boolean contains(Instant value) {
            return !value.isBefore(start) && value.isBefore(end);
        }
    }
}
