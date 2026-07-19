package com.adept.api.webhook;

import com.adept.api.common.domain.WebhookSource;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface RawWebhookEventRepository
    extends JpaRepository<RawWebhookEvent, UUID> {

    Optional<RawWebhookEvent> findBySourceAndDeliveryId(
        WebhookSource source,
        String deliveryId
    );
}
