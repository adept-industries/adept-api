package com.adept.api.notification;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface NotificationDeliveryRepository extends JpaRepository<NotificationDelivery, UUID> {

    @Query(
        value = """
            SELECT *
            FROM notification_deliveries
            WHERE channel = 'EMAIL'
              AND status IN ('PENDING', 'FAILED', 'SENDING')
              AND attempts < 5
            ORDER BY created_at ASC
            FOR UPDATE SKIP LOCKED
            LIMIT :limit
            """,
        nativeQuery = true
    )
    List<NotificationDelivery> lockClaimableEmailDeliveries(@Param("limit") int limit);
}
