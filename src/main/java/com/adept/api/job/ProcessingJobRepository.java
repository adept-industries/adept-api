package com.adept.api.job;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ProcessingJobRepository
    extends JpaRepository<ProcessingJob, UUID> {

    @Query(
        value = """
            SELECT *
            FROM processing_jobs
            WHERE status IN ('PENDING', 'FAILED')
              AND available_at <= now()
            ORDER BY priority ASC, created_at ASC
            FOR UPDATE SKIP LOCKED
            LIMIT :limit
            """,
        nativeQuery = true
    )
    List<ProcessingJob> lockClaimableJobs(
        @Param("limit") int limit
    );
}
