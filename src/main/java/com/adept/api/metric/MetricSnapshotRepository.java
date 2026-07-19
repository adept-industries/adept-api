package com.adept.api.metric;

import com.adept.api.common.domain.MetricType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface MetricSnapshotRepository
    extends JpaRepository<MetricSnapshot, UUID> {

    @Query("""
        select m
        from MetricSnapshot m
        where m.workspace.id = :workspaceId
          and m.repository.id in :repositoryIds
          and m.metricType = :metricType
          and m.periodStart >= :from
          and m.periodEnd <= :to
        order by m.periodStart
        """)
    List<MetricSnapshot> findDashboardSeries(
        @Param("workspaceId") UUID workspaceId,
        @Param("repositoryIds") Collection<UUID> repositoryIds,
        @Param("metricType") MetricType metricType,
        @Param("from") Instant from,
        @Param("to") Instant to
    );
}
