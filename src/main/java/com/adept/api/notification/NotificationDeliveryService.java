package com.adept.api.notification;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.adept.api.common.domain.NotificationStatus;
import com.adept.api.mail.AccountMailService;

@Service
public class NotificationDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(NotificationDeliveryService.class);
    private static final int MAX_ATTEMPTS = 5;

    private final NotificationDeliveryRepository deliveryRepository;
    private final AccountMailService mailService;
    private final TransactionTemplate transactionTemplate;
    private final int batchSize;

    public NotificationDeliveryService(
        NotificationDeliveryRepository deliveryRepository,
        AccountMailService mailService,
        PlatformTransactionManager transactionManager,
        @Value("${app.notification.delivery-batch-size:10}") int batchSize
    ) {
        this.deliveryRepository = deliveryRepository;
        this.mailService = mailService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${app.notification.poll-interval-ms:3000}")
    public void processPendingDeliveries() {
        while (true) {
            int processed = processBatch();
            if (processed < batchSize) {
                break;
            }
        }
    }

    public int processBatch() {
        List<NotificationDelivery> claimed = transactionTemplate.execute(status -> {
            List<NotificationDelivery> deliveries = deliveryRepository.lockClaimableEmailDeliveries(batchSize);
            for (NotificationDelivery delivery : deliveries) {
                delivery.setStatus(NotificationStatus.SENDING);
                deliveryRepository.save(delivery);
            }
            return deliveries;
        });

        if (claimed == null || claimed.isEmpty()) {
            return 0;
        }

        for (NotificationDelivery delivery : claimed) {
            dispatchDelivery(delivery);
        }

        return claimed.size();
    }

    private void dispatchDelivery(NotificationDelivery delivery) {
        String destination = delivery.getDestination();
        Map<String, Object> payload = delivery.getPayload() != null ? delivery.getPayload() : Map.of();

        String subject = extractString(payload, "subject", "[Adept Alert] Notification Triggered");
        String textBody = extractString(payload, "text", "An alert rule condition was met. Please check your dashboard.");

        try {
            mailService.sendAlert(destination, subject, textBody);

            transactionTemplate.executeWithoutResult(status -> {
                deliveryRepository.findById(delivery.getId()).ifPresent(persisted -> {
                    persisted.setStatus(NotificationStatus.SENT);
                    persisted.setSentAt(Instant.now());
                    persisted.setLastError(null);
                    deliveryRepository.save(persisted);
                });
            });

            log.info("alert_notification_delivered deliveryId={} destination={}", delivery.getId(), destination);
        } catch (Exception ex) {
            String errorMessage = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
            log.error("alert_notification_delivery_failed deliveryId={} destination={} error={}",
                delivery.getId(), destination, errorMessage, ex);

            transactionTemplate.executeWithoutResult(status -> {
                deliveryRepository.findById(delivery.getId()).ifPresent(persisted -> {
                    int attempts = persisted.getAttempts() + 1;
                    persisted.setAttempts(attempts);
                    persisted.setLastError(errorMessage);
                    persisted.setStatus(attempts >= MAX_ATTEMPTS ? NotificationStatus.DEAD : NotificationStatus.FAILED);
                    deliveryRepository.save(persisted);
                });
            });
        }
    }

    private String extractString(Map<String, Object> payload, String key, String fallback) {
        Object val = payload.get(key);
        if (val instanceof String s && !s.isBlank()) {
            return s;
        }
        return fallback;
    }
}
