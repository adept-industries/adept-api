package com.adept.api.notification;

import java.time.Clock;
import java.time.Duration;
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
    private final Clock clock;
    private final int batchSize;
    private final Duration retryDelay;
    private final Duration sendingTimeout;

    public NotificationDeliveryService(
        NotificationDeliveryRepository deliveryRepository,
        AccountMailService mailService,
        PlatformTransactionManager transactionManager,
        Clock clock,
        @Value("${app.notification.delivery-batch-size:10}") int batchSize,
        @Value("${app.notification.retry-delay-seconds:30}") long retryDelaySeconds,
        @Value("${app.notification.sending-timeout-seconds:300}") long sendingTimeoutSeconds
    ) {
        this.deliveryRepository = deliveryRepository;
        this.mailService = mailService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
        this.batchSize = Math.max(1, batchSize);
        this.retryDelay = Duration.ofSeconds(Math.max(1, retryDelaySeconds));
        this.sendingTimeout = Duration.ofSeconds(Math.max(1, sendingTimeoutSeconds));
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
        Instant now = clock.instant();
        List<NotificationDelivery> claimed = transactionTemplate.execute(status -> {
            List<NotificationDelivery> deliveries = deliveryRepository.lockClaimableEmailDeliveries(
                batchSize,
                MAX_ATTEMPTS,
                now.minus(retryDelay),
                now.minus(sendingTimeout)
            );
            for (NotificationDelivery delivery : deliveries) {
                delivery.setStatus(NotificationStatus.SENDING);
                delivery.setAttempts(delivery.getAttempts() + 1);
            }
            return deliveryRepository.saveAllAndFlush(deliveries);
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
        String htmlBody = extractString(payload, "html", null);

        try {
            mailService.sendAlertHtml(destination, subject, textBody, htmlBody);

            transactionTemplate.executeWithoutResult(status -> {
                deliveryRepository.findById(delivery.getId()).ifPresent(persisted -> {
                    persisted.setStatus(NotificationStatus.SENT);
                    persisted.setSentAt(clock.instant());
                    persisted.setLastError(null);
                    deliveryRepository.save(persisted);
                });
            });

            log.info(
                "alert_notification_delivered deliveryId={} attempt={}",
                delivery.getId(),
                delivery.getAttempts()
            );
        } catch (Exception ex) {
            String errorMessage = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
            NotificationStatus failureStatus = delivery.getAttempts() >= MAX_ATTEMPTS
                ? NotificationStatus.DEAD
                : NotificationStatus.FAILED;

            transactionTemplate.executeWithoutResult(status -> {
                deliveryRepository.findById(delivery.getId()).ifPresent(persisted -> {
                    persisted.setLastError(errorMessage);
                    persisted.setStatus(failureStatus);
                    deliveryRepository.save(persisted);
                });
            });

            log.error(
                "alert_notification_delivery_failed deliveryId={} attempt={} status={} error={}",
                delivery.getId(),
                delivery.getAttempts(),
                failureStatus,
                errorMessage,
                ex
            );
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
