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

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.adept.api.common.domain.DeploymentStatus;
import com.adept.api.common.domain.IncidentSeverity;
import com.adept.api.common.domain.IncidentSource;
import com.adept.api.common.domain.MembershipRole;
import com.adept.api.common.domain.MetricGranularity;
import com.adept.api.common.domain.MetricType;
import com.adept.api.common.error.ApiException;
import com.adept.api.common.error.NotFoundException;
import com.adept.api.common.error.ProblemCode;
import com.adept.api.deployment.ChangeFailureRateRow;
import com.adept.api.deployment.Deployment;
import com.adept.api.deployment.DeploymentRepository;
import com.adept.api.incident.IncidentRepository;
import com.adept.api.incident.RecoveryTimeRow;
import com.adept.api.integration.github.GitRepository;
import com.adept.api.integration.github.GitRepositoryRepository;
import com.adept.api.metric.dto.ChangeFailureRateDetailDto;
import com.adept.api.metric.dto.ChangeFailureRateDetailsResponse;
import com.adept.api.metric.dto.ChangeFailureRateIncidentRefDto;
import com.adept.api.metric.dto.ChangeLeadTimeDetailDto;
import com.adept.api.metric.dto.ChangeLeadTimeDetailsResponse;
import com.adept.api.metric.dto.DeploymentFrequencyDetailDto;
import com.adept.api.metric.dto.DeploymentFrequencyDetailsResponse;
import com.adept.api.metric.dto.DoraMetricsSeriesResponse;
import com.adept.api.metric.dto.DoraMetricsSummaryResponse;
import com.adept.api.metric.dto.MetricSeriesItemDto;
import com.adept.api.metric.dto.MetricSummaryDto;
import com.adept.api.metric.dto.RecoveryDeploymentRefDto;
import com.adept.api.metric.dto.RecoveryTimeDetailDto;
import com.adept.api.metric.dto.RecoveryTimeDetailsResponse;
import com.adept.api.project.ProjectRepository;
import com.adept.api.project.ProjectRepositoryLinkRepository;
import com.adept.api.pullrequest.ChangeLeadTimeRow;
import com.adept.api.pullrequest.PullRequestRepository;
import com.adept.api.security.AuthenticatedPrincipal;
import com.adept.api.security.RepositoryScopeService;
import com.adept.api.workspace.WorkspaceRepository;

@Service
@Transactional(readOnly = true)
public class MetricService {

    static final String CALCULATION_VERSION = "dora-v3";
    private static final Duration STALE_AFTER = Duration.ofHours(24);

    private final MetricSnapshotRepository metricSnapshotRepository;
    private final GitRepositoryRepository gitRepositoryRepository;
    private final ProjectRepository projectRepository;
    private final ProjectRepositoryLinkRepository projectRepositoryLinkRepository;
    private final RepositoryScopeService repositoryScopeService;
    private final WorkspaceRepository workspaceRepository;
    private final DeploymentRepository deploymentRepository;
    private final PullRequestRepository pullRequestRepository;
    private final IncidentRepository incidentRepository;

    public MetricService(
            MetricSnapshotRepository metricSnapshotRepository,
            GitRepositoryRepository gitRepositoryRepository,
            ProjectRepository projectRepository,
            ProjectRepositoryLinkRepository projectRepositoryLinkRepository,
            RepositoryScopeService repositoryScopeService,
            WorkspaceRepository workspaceRepository,
            DeploymentRepository deploymentRepository,
            PullRequestRepository pullRequestRepository,
            IncidentRepository incidentRepository) {
        this.metricSnapshotRepository = metricSnapshotRepository;
        this.gitRepositoryRepository = gitRepositoryRepository;
        this.projectRepository = projectRepository;
        this.projectRepositoryLinkRepository = projectRepositoryLinkRepository;
        this.repositoryScopeService = repositoryScopeService;
        this.workspaceRepository = workspaceRepository;
        this.deploymentRepository = deploymentRepository;
        this.pullRequestRepository = pullRequestRepository;
        this.incidentRepository = incidentRepository;
    }

    public DoraMetricsSummaryResponse getSummary(
            AuthenticatedPrincipal principal,
            UUID projectId,
            UUID repositoryId,
            Instant from,
            Instant to) {
        MetricRange range = validateRange(from, to);
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

        Instant calculatedAt = completeCalculation(repositoryIds, snapshots);
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
        MetricRange range = validateRange(from, to);
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

        Instant calculatedAt = completeCalculation(repositoryIds, snapshots);
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

    public DeploymentFrequencyDetailsResponse getDeploymentFrequencyDetails(
            AuthenticatedPrincipal principal,
            UUID projectId,
            UUID repositoryId,
            Instant from,
            Instant to,
            int page,
            int size) {
        MetricRange range = validateRange(from, to);
        String timezone = workspaceTimezone(principal);
        List<UUID> repositoryIds = resolveAccessibleRepositoryIds(principal, projectId, repositoryId);

        if (repositoryIds.isEmpty()) {
            return new DeploymentFrequencyDetailsResponse(
                principal.workspaceId(),
                projectId,
                repositoryId,
                0,
                range.start(),
                range.end(),
                timezone,
                page,
                size,
                0L,
                0,
                List.of()
            );
        }

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "finishedAt"));
        Page<Deployment> deploymentsPage = deploymentRepository.findSuccessfulProductionDeployments(
            repositoryIds,
            range.start(),
            range.end(),
            pageable
        );

        List<DeploymentFrequencyDetailDto> items = deploymentsPage.getContent().stream()
            .map(this::toDeploymentFrequencyDetailDto)
            .toList();

        return new DeploymentFrequencyDetailsResponse(
            principal.workspaceId(),
            projectId,
            repositoryId,
            repositoryIds.size(),
            range.start(),
            range.end(),
            timezone,
            deploymentsPage.getNumber(),
            deploymentsPage.getSize(),
            deploymentsPage.getTotalElements(),
            deploymentsPage.getTotalPages(),
            items
        );
    }

    public ChangeLeadTimeDetailsResponse getChangeLeadTimeDetails(
            AuthenticatedPrincipal principal,
            UUID projectId,
            UUID repositoryId,
            Instant from,
            Instant to,
            int page,
            int size) {
        MetricRange range = validateRange(from, to);
        String timezone = workspaceTimezone(principal);
        List<UUID> repositoryIds = resolveAccessibleRepositoryIds(principal, projectId, repositoryId);

        if (repositoryIds.isEmpty()) {
            return new ChangeLeadTimeDetailsResponse(
                principal.workspaceId(),
                projectId,
                repositoryId,
                0,
                range.start(),
                range.end(),
                timezone,
                page,
                size,
                0L,
                0,
                List.of()
            );
        }

        Pageable pageable = PageRequest.of(page, size);
        Page<ChangeLeadTimeRow> rowsPage = pullRequestRepository.findChangeLeadTimeRows(
            repositoryIds,
            range.start(),
            range.end(),
            pageable
        );

        List<ChangeLeadTimeDetailDto> items = rowsPage.getContent().stream()
            .map(this::toChangeLeadTimeDetailDto)
            .toList();

        return new ChangeLeadTimeDetailsResponse(
            principal.workspaceId(),
            projectId,
            repositoryId,
            repositoryIds.size(),
            range.start(),
            range.end(),
            timezone,
            rowsPage.getNumber(),
            rowsPage.getSize(),
            rowsPage.getTotalElements(),
            rowsPage.getTotalPages(),
            items
        );
    }

    private ChangeLeadTimeDetailDto toChangeLeadTimeDetailDto(ChangeLeadTimeRow row) {
        Instant firstCommit = row.getFirstCommitAt();
        Instant opened     = row.getOpenedAt();
        Instant merged     = row.getMergedAt();
        Instant deployed   = row.getDeployedAt();

        Long leadTime   = (firstCommit != null && deployed != null && !firstCommit.isAfter(deployed))
            ? Duration.between(firstCommit, deployed).toSeconds() : null;
        Long codingTime = (firstCommit != null && opened != null && !firstCommit.isAfter(opened))
            ? Duration.between(firstCommit, opened).toSeconds() : null;
        Long reviewTime = (opened != null && merged != null && !opened.isAfter(merged))
            ? Duration.between(opened, merged).toSeconds() : null;
        Long deployTime = (merged != null && deployed != null && !merged.isAfter(deployed))
            ? Duration.between(merged, deployed).toSeconds() : null;

        String prUrl = row.getRepositoryFullName() != null
            ? "https://github.com/" + row.getRepositoryFullName() + "/pull/" + row.getPrNumber()
            : null;

        return new ChangeLeadTimeDetailDto(
            row.getPrId(),
            row.getPrNumber(),
            row.getPrTitle(),
            prUrl,
            row.getAuthorLogin(),
            row.getRepositoryId(),
            row.getRepositoryName(),
            row.getRepositoryOwnerLogin(),
            row.getRepositoryFullName(),
            firstCommit,
            opened,
            merged,
            deployed,
            leadTime,
            codingTime,
            reviewTime,
            deployTime,
            row.getDeploymentEnvironment(),
            row.getDeploymentCommitSha()
        );
    }

    public RecoveryTimeDetailsResponse getRecoveryTimeDetails(
            AuthenticatedPrincipal principal,
            UUID projectId,
            UUID repositoryId,
            Instant from,
            Instant to,
            int page,
            int size) {
        MetricRange range = validateRange(from, to);
        String timezone = workspaceTimezone(principal);
        List<UUID> repositoryIds = resolveAccessibleRepositoryIds(principal, projectId, repositoryId);

        if (repositoryIds.isEmpty()) {
            return new RecoveryTimeDetailsResponse(
                principal.workspaceId(),
                projectId,
                repositoryId,
                0,
                range.start(),
                range.end(),
                timezone,
                page,
                size,
                0L,
                0,
                List.of()
            );
        }

        Pageable pageable = PageRequest.of(page, size);
        Page<RecoveryTimeRow> rowsPage = incidentRepository.findRecoveryTimeRows(
            repositoryIds,
            range.start(),
            range.end(),
            pageable
        );

        List<RecoveryTimeDetailDto> items = rowsPage.getContent().stream()
            .map(this::toRecoveryTimeDetailDto)
            .toList();

        return new RecoveryTimeDetailsResponse(
            principal.workspaceId(),
            projectId,
            repositoryId,
            repositoryIds.size(),
            range.start(),
            range.end(),
            timezone,
            rowsPage.getNumber(),
            rowsPage.getSize(),
            rowsPage.getTotalElements(),
            rowsPage.getTotalPages(),
            items
        );
    }

    private RecoveryTimeDetailDto toRecoveryTimeDetailDto(RecoveryTimeRow row) {
        Instant detected = row.getDetectedAt();
        Instant resolved = row.getEffectiveResolvedAt() != null ? row.getEffectiveResolvedAt() : row.getResolvedAt();

        Long recoverySeconds = (detected != null && resolved != null && !detected.isAfter(resolved))
            ? Duration.between(detected, resolved).toSeconds() : null;

        RecoveryDeploymentRefDto failed = row.getFailedDeploymentId() == null ? null
            : new RecoveryDeploymentRefDto(
                row.getFailedDeploymentId(),
                row.getFailedDeploymentCommitSha(),
                row.getFailedDeploymentEnvironment(),
                row.getFailedDeploymentFinishedAt()
            );
        RecoveryDeploymentRefDto recovery = row.getRecoveryDeploymentId() == null ? null
            : new RecoveryDeploymentRefDto(
                row.getRecoveryDeploymentId(),
                row.getRecoveryDeploymentCommitSha(),
                row.getRecoveryDeploymentEnvironment(),
                row.getRecoveryDeploymentFinishedAt()
            );

        return new RecoveryTimeDetailDto(
            row.getIncidentId(),
            row.getTitle(),
            row.getSource() != null ? IncidentSource.valueOf(row.getSource()) : null,
            row.getSeverity() != null ? IncidentSeverity.valueOf(row.getSeverity()) : IncidentSeverity.UNKNOWN,
            row.getRepositoryId(),
            row.getRepositoryName(),
            row.getRepositoryFullName(),
            detected,
            resolved,
            recoverySeconds,
            failed,
            recovery
        );
    }

    public ChangeFailureRateDetailsResponse getChangeFailureRateDetails(
            AuthenticatedPrincipal principal,
            UUID projectId,
            UUID repositoryId,
            Instant from,
            Instant to,
            int page,
            int size) {
        MetricRange range = validateRange(from, to);
        String timezone = workspaceTimezone(principal);
        List<UUID> repositoryIds = resolveAccessibleRepositoryIds(principal, projectId, repositoryId);

        if (repositoryIds.isEmpty()) {
            return new ChangeFailureRateDetailsResponse(
                principal.workspaceId(),
                projectId,
                repositoryId,
                0,
                range.start(),
                range.end(),
                timezone,
                page,
                size,
                0L,
                0,
                0L,
                0L,
                0.0,
                List.of()
            );
        }

        Pageable pageable = PageRequest.of(page, size);
        Page<ChangeFailureRateRow> rowsPage = deploymentRepository.findChangeFailureRateRows(
            repositoryIds,
            range.start(),
            range.end(),
            pageable
        );
        long totalDeployments = rowsPage.getTotalElements();
        long failedDeployments = totalDeployments == 0 ? 0L
            : deploymentRepository.countFailedProductionDeployments(repositoryIds, range.start(), range.end());
        double failureRatePercent = totalDeployments == 0 ? 0.0
            : decimal(failedDeployments * 100.0 / totalDeployments).doubleValue();

        List<ChangeFailureRateDetailDto> items = rowsPage.getContent().stream()
            .map(this::toChangeFailureRateDetailDto)
            .toList();

        return new ChangeFailureRateDetailsResponse(
            principal.workspaceId(),
            projectId,
            repositoryId,
            repositoryIds.size(),
            range.start(),
            range.end(),
            timezone,
            rowsPage.getNumber(),
            rowsPage.getSize(),
            totalDeployments,
            rowsPage.getTotalPages(),
            totalDeployments,
            failedDeployments,
            failureRatePercent,
            items
        );
    }

    private ChangeFailureRateDetailDto toChangeFailureRateDetailDto(ChangeFailureRateRow row) {
        DeploymentStatus status = row.getStatus() != null ? DeploymentStatus.valueOf(row.getStatus()) : null;

        ChangeFailureRateIncidentRefDto incident = row.getIncidentId() == null ? null
            : new ChangeFailureRateIncidentRefDto(
                row.getIncidentId(),
                row.getIncidentTitle(),
                row.getIncidentSeverity() != null
                    ? IncidentSeverity.valueOf(row.getIncidentSeverity()) : IncidentSeverity.UNKNOWN
            );

        // Engine parity: a deployment fails when it ended in FAILURE or an incident links to it.
        boolean isFailure = status == DeploymentStatus.FAILURE || incident != null;

        return new ChangeFailureRateDetailDto(
            row.getDeploymentId(),
            row.getRepositoryId(),
            row.getRepositoryName(),
            row.getRepositoryFullName(),
            row.getFinishedAt(),
            row.getEnvironment(),
            status,
            row.getCommitSha(),
            isFailure,
            incident
        );
    }

    private DeploymentFrequencyDetailDto toDeploymentFrequencyDetailDto(Deployment d) {
        Long durationSeconds = null;
        if (d.getStartedAt() != null && d.getFinishedAt() != null && !d.getStartedAt().isAfter(d.getFinishedAt())) {
            durationSeconds = Duration.between(d.getStartedAt(), d.getFinishedAt()).toSeconds();
        }
        return new DeploymentFrequencyDetailDto(
            d.getId(),
            d.getRepository().getId(),
            d.getRepository().getName(),
            d.getRepository().getFullName(),
            d.getFinishedAt(),
            d.getEnvironment(),
            d.getSource(),
            d.getCommitSha(),
            durationSeconds
        );
    }

    private MetricRange validateRange(Instant from, Instant to) {
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
        if (projectId != null) {
            projectRepository.findByIdAndWorkspaceId(projectId, principal.workspaceId())
                .orElseThrow(() -> new NotFoundException(ProblemCode.PROJECT_NOT_FOUND));

            List<UUID> projectRepositoryIds;
            if (principal.role() == MembershipRole.MANAGER) {
                projectRepositoryIds = projectRepositoryLinkRepository
                    .findAllWithRepositoryByProjectId(projectId)
                    .stream()
                    .map(link -> link.getRepository())
                    .filter(MetricService::isMetricRepository)
                    .map(GitRepository::getId)
                    .distinct()
                    .toList();
            } else {
                projectRepositoryIds = projectRepositoryLinkRepository.findAllReadableByLead(
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

            if (repositoryId == null) {
                return projectRepositoryIds;
            }
            if (!projectRepositoryIds.contains(repositoryId)) {
                throw new NotFoundException(ProblemCode.REPOSITORY_NOT_FOUND);
            }
            return List.of(repositoryId);
        }

        if (repositoryId != null) {
            GitRepository repository = repositoryScopeService.requireReadableRepository(
                principal,
                repositoryId
            );
            if (!isMetricRepository(repository)) {
                throw new NotFoundException(ProblemCode.REPOSITORY_NOT_FOUND);
            }
            return List.of(repositoryId);
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
        if (sortedValues.isEmpty()) {
            return 0.0;
        }
        if (sortedValues.size() == 1) {
            return sortedValues.get(0);
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

    private static Instant completeCalculation(
            List<UUID> repositoryIds,
            List<MetricSnapshot> snapshots) {
        if (repositoryIds.isEmpty()) {
            return null;
        }
        Map<UUID, Instant> latestByRepository = new HashMap<>();
        for (MetricSnapshot snapshot : snapshots) {
            Instant calculatedAt = snapshot.getCalculatedAt();
            if (calculatedAt == null || snapshot.getRepository() == null) {
                continue;
            }
            latestByRepository.merge(
                snapshot.getRepository().getId(),
                calculatedAt,
                (left, right) -> left.isAfter(right) ? left : right
            );
        }
        if (!latestByRepository.keySet().containsAll(repositoryIds)) {
            return null;
        }
        return repositoryIds.stream()
            .map(latestByRepository::get)
            .min(Instant::compareTo)
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
