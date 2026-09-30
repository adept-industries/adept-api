package com.adept.api.metric;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
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
 *
 * <p>Only DAY snapshots are read. The chart's periods are generated here as calendar days,
 * Monday-start weeks or months in the workspace timezone (matching the engine's buckets), and
 * each pull request is placed by its exact merge time, so quiet periods still appear and a
 * new period starts on its own without waiting for a recalculation.
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

        List<MetricSnapshot> daySnapshots = findSnapshots(principal, repositoryIds, MetricGranularity.DAY, range);

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
            repositoryIds.isEmpty()
                ? List.of()
                : series(pooled, range, effectiveGranularity, zone(timezone))
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

    private static List<CycleTimePeriodDto> series(
            Map<CycleTimeStage, List<StageObservation>> pooled,
            MetricRange range,
            MetricGranularity granularity,
            ZoneId zone) {
        List<CycleTimePeriodDto> result = new ArrayList<>();
        LocalDate day = periodStart(range.start().atZone(zone).toLocalDate(), granularity);
        Instant start = day.atStartOfDay(zone).toInstant();
        while (start.isBefore(range.end())) {
            day = nextPeriod(day, granularity);
            Instant end = day.atStartOfDay(zone).toInstant();
            Map<CycleTimeStage, List<StageObservation>> inPeriod = new EnumMap<>(CycleTimeStage.class);
            for (Map.Entry<CycleTimeStage, List<StageObservation>> entry : pooled.entrySet()) {
                Instant periodStart = start;
                inPeriod.put(entry.getKey(), entry.getValue().stream()
                    .filter(item -> !item.at().isBefore(periodStart) && item.at().isBefore(end))
                    .toList());
            }
            List<CycleTimeStageDto> periodStages = stageSummaries(inPeriod);
            result.add(new CycleTimePeriodDto(
                start,
                end,
                distinctPullRequests(inPeriod).size(),
                bottleneck(periodStages),
                periodStages
            ));
            start = end;
        }
        return result;
    }

    static LocalDate periodStart(LocalDate date, MetricGranularity granularity) {
        return switch (granularity) {
            case DAY -> date;
            case WEEK -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case MONTH -> date.withDayOfMonth(1);
        };
    }

    private static LocalDate nextPeriod(LocalDate start, MetricGranularity granularity) {
        return switch (granularity) {
            case DAY -> start.plusDays(1);
            case WEEK -> start.plusWeeks(1);
            case MONTH -> start.plusMonths(1);
        };
    }

    private static ZoneId zone(String timezone) {
        try {
            return ZoneId.of(timezone);
        } catch (DateTimeException | NullPointerException ignored) {
            return ZoneOffset.UTC;
        }
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
                        at,
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

    private record StageObservation(String key, Instant at, double hours, boolean reviewed) {}
}
