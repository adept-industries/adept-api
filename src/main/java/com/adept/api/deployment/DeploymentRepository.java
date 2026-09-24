package com.adept.api.deployment;

import java.time.Instant;
import java.util.Collection;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface DeploymentRepository extends JpaRepository<Deployment, UUID> {

    @Query(value = """
        SELECT d FROM Deployment d
        JOIN FETCH d.repository r
        WHERE d.repository.id IN :repositoryIds
          AND d.production = true
          AND d.status = com.adept.api.common.domain.DeploymentStatus.SUCCESS
          AND d.finishedAt >= :from
          AND d.finishedAt < :to
        ORDER BY d.finishedAt DESC
        """,
        countQuery = """
        SELECT count(d) FROM Deployment d
        WHERE d.repository.id IN :repositoryIds
          AND d.production = true
          AND d.status = com.adept.api.common.domain.DeploymentStatus.SUCCESS
          AND d.finishedAt >= :from
          AND d.finishedAt < :to
        """)
    Page<Deployment> findSuccessfulProductionDeployments(
        @Param("repositoryIds") Collection<UUID> repositoryIds,
        @Param("from") Instant from,
        @Param("to") Instant to,
        Pageable pageable
    );
}
