package com.adept.api.metric;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.adept.api.common.domain.MetricGranularity;
import com.adept.api.common.domain.MetricType;
import com.adept.api.metric.MetricService.MetricRange;
import com.adept.api.metric.dto.CycleTimePeriodDto;
import com.adept.api.metric.dto.CycleTimeResponse;
import com.adept.api.metric.dto.CycleTimeStageDto;
import com.adept.api.security.AuthenticatedPrincipal;

/**
 * Serves code-review cycle time from pooled per-PR observations written by the engine.
 * Every observation is keyed by pull request and timestamped at its merge, so all stages
 * describe the same cohort of merged pull requests.
 */
@Service
@Transactional(readOnly = true)
public class CycleTimeService {

    static final String CALCULATION_VERSION = "cycle-time-v2";

    private final MetricService metricService;
    private final MetricSnapshotRepository metricSnapshotRepository;

    public CycleTimeService(MetricService metricService, MetricSnapshotRepository metricSnapshotRepository) {
        this.metricService = metricService;
        this.metricSnapshotRepository = metricSnapshotRepository;
    }

    public CycleTimeResponse getCycleTime(
            AuthenticatedPrincipal principal,
            UUID projectId,
            UUID repositoryId,
            MetricGranularity granularity,
            Instant from,
            Instant to) {
        MetricRange range = metricService.validateRange(from, to);
        List<UUID> repositoryIds = metricService.resolveAccessibleRepositoryIds(principal, projectId, repositoryId);
        MetricGranularity effectiveGranularity = granularity != null ? granularity : MetricGranularity.WEEK;
        String timezone = metricService.workspaceTimezone(principal);

        // DAY snapshots give exact range totals; the chart uses the requested granularity.
        List<MetricSnapshot> daySnapshots = findSnapshots(principal, repositoryIds, MetricGranularity.DAY, range);
        List<MetricSnapshot> seriesSnapshots = effectiveGranularity == MetricGranularity.DAY
            ? daySnapshots
            : findSnapshots(principal, repositoryIds, effectiveGranularity, range);

        Map<CycleTimeStage, List<StageObservation>> pooled = observationsByStage(daySnapshots, range);
        List<CycleTimeStageDto> stages = stageSummaries(pooled);
        Map<String, StageObservation> pullRequests = distinctPullRequests(pooled);
        Instant calculatedAt = MetricService.completeCalculation(repositoryIds, daySnapshots);

        return new CycleTimeResponse(
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
            MetricService.isStale(calculatedAt),
            pullRequests.size(),
            (int) pullRequests.values().stream().filter(item -> !item.reviewed()).count(),
            bottleneck(stages),
            stages,
            series(seriesSnapshots, range)
        );
    }

    private List<MetricSnapshot> findSnapshots(
            AuthenticatedPrincipal principal,
            List<UUID> repositoryIds,
            MetricGranularity granularity,
            MetricRange range) {
        if (repositoryIds.isEmpty()) {
            return List.of();
        }
        return metricSnapshotRepository.findSnapshots(
            principal.workspaceId(),
            repositoryIds,
            granularity,
            CALCULATION_VERSION,
            range.start(),
            range.end()
        );
    }

    private static List<CycleTimeStageDto> stageSummaries(Map<CycleTimeStage, List<StageObservation>> pooled) {
        List<CycleTimeStageDto> result = new ArrayList<>();
        for (CycleTimeStage stage : CycleTimeStage.values()) {
            List<Double> hours = sortedHours(pooled.get(stage));
            result.add(new CycleTimeStageDto(
                stage,
                MetricService.decimal(MetricService.percentile(hours, 0.50)),
                MetricService.decimal(MetricService.percentile(hours, 0.75)),
                hours.size()
            ));
        }
        return result;
    }

    private static CycleTimeStage bottleneck(List<CycleTimeStageDto> stages) {
        // A zero-minute median is never a bottleneck, even when it is the only stage with data.
        return stages.stream()
            .filter(stage -> stage.sampleSize() > 0 && stage.medianHours().signum() > 0)
            .max(Comparator.comparing(CycleTimeStageDto::medianHours))
            .map(CycleTimeStageDto::stage)
            .orElse(null);
    }

    private static List<CycleTimePeriodDto> series(List<MetricSnapshot> snapshots, MetricRange requestedRange) {
        record Period(Instant start, Instant end) {}
        Map<Period, List<MetricSnapshot>> periods = new LinkedHashMap<>();
        snapshots.stream()
            .sorted(Comparator.comparing(MetricSnapshot::getPeriodStart))
            .forEach(snapshot -> periods
                .computeIfAbsent(new Period(snapshot.getPeriodStart(), snapshot.getPeriodEnd()), ignored -> new ArrayList<>())
                .add(snapshot));

        List<CycleTimePeriodDto> result = new ArrayList<>();
        for (Map.Entry<Period, List<MetricSnapshot>> entry : periods.entrySet()) {
            Period period = entry.getKey();
            // Partial first and last periods only count merges inside the requested range.
            MetricRange clipped = new MetricRange(
                period.start().isAfter(requestedRange.start()) ? period.start() : requestedRange.start(),
                period.end().isBefore(requestedRange.end()) ? period.end() : requestedRange.end()
            );
            Map<CycleTimeStage, List<StageObservation>> pooled = observationsByStage(entry.getValue(), clipped);
            result.add(new CycleTimePeriodDto(
                period.start(),
                period.end(),
                distinctPullRequests(pooled).size(),
                stageSummaries(pooled)
            ));
        }
        return result;
    }

    private static Map<String, StageObservation> distinctPullRequests(
            Map<CycleTimeStage, List<StageObservation>> pooled) {
        Map<String, StageObservation> byKey = new LinkedHashMap<>();
        pooled.values().forEach(observations -> observations.forEach(item -> byKey.putIfAbsent(item.key(), item)));
        return byKey;
    }

    private static List<Double> sortedHours(List<StageObservation> observations) {
        return observations.stream().map(StageObservation::hours).sorted().toList();
    }

    private static Map<CycleTimeStage, List<StageObservation>> observationsByStage(
            List<MetricSnapshot> snapshots,
            MetricRange range) {
        Map<MetricType, CycleTimeStage> stagesByType = new HashMap<>();
        for (CycleTimeStage stage : CycleTimeStage.values()) {
            stagesByType.put(stage.metricType(), stage);
        }
        Map<CycleTimeStage, List<StageObservation>> result = new EnumMap<>(CycleTimeStage.class);
        Map<CycleTimeStage, Set<String>> seen = new EnumMap<>(CycleTimeStage.class);
        for (CycleTimeStage stage : CycleTimeStage.values()) {
            result.put(stage, new ArrayList<>());
            seen.put(stage, new HashSet<>());
        }
        for (MetricSnapshot snapshot : snapshots) {
            CycleTimeStage stage = stagesByType.get(snapshot.getMetricType());
            Object raw = snapshot.getDimensions() == null ? null : snapshot.getDimensions().get("observations");
            if (stage == null || !(raw instanceof List<?> list)) {
                continue;
            }
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> map)) {
                    continue;
                }
                try {
                    String key = String.valueOf(map.get("key"));
                    Instant at = Instant.parse(String.valueOf(map.get("at")));
                    // Repositories share one pooled set; a PR is counted once per stage.
                    if (!range.contains(at) || map.get("key") == null || !seen.get(stage).add(key)) {
                        continue;
                    }
                    double hours = Double.parseDouble(String.valueOf(map.get("value")));
                    result.get(stage).add(new StageObservation(
                        key,
                        hours,
                        Boolean.TRUE.equals(map.get("reviewed"))
                    ));
                } catch (RuntimeException ignored) {
                    // Malformed observations are excluded instead of corrupting an aggregate.
                }
            }
        }
        return result;
    }

    private record StageObservation(String key, double hours, boolean reviewed) {}
}
