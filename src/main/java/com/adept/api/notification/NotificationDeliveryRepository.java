package com.adept.api.notification;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationDeliveryRepository extends JpaRepository<NotificationDelivery, UUID> {

    @Query(
        value = """
            SELECT *
            FROM notification_deliveries
            WHERE channel = 'EMAIL'
              AND attempts < :maxAttempts
              AND (
                    status = 'PENDING'
                    OR (status = 'FAILED' AND updated_at <= :retryBefore)
                    OR (status = 'SENDING' AND updated_at <= :sendingBefore)
              )
            ORDER BY created_at ASC
            FOR UPDATE SKIP LOCKED
            LIMIT :limit
            """,
        nativeQuery = true
    )
    List<NotificationDelivery> lockClaimableEmailDeliveries(
        @Param("limit") int limit,
        @Param("maxAttempts") int maxAttempts,
        @Param("retryBefore") Instant retryBefore,
        @Param("sendingBefore") Instant sendingBefore
    );
}
