package com.skateboard.notification.application.service;

import com.skateboard.notification.application.port.out.DeliveryRepositoryPort;
import com.skateboard.notification.application.port.out.DeviceRepositoryPort;
import com.skateboard.notification.application.port.out.PushNotificationProviderPort;
import com.skateboard.notification.application.port.out.PushReceipt;
import com.skateboard.notification.domain.model.NotificationDelivery;
import com.skateboard.notification.infrastructure.push.ReceiptProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Asks the provider what became of messages it accepted, and settles the
 * delivery rows accordingly.
 *
 * <p>Acceptance is not delivery. A ticket only says Expo took the message, and
 * Expo documents that some failures — `DeviceNotRegistered` among them — are
 * reported *only* in the receipt. Without this pass a token that died between
 * registration and the send is never noticed, so it stays enabled and costs a
 * wasted message on every notification from then on.
 *
 * <p>Also the only thing that ever sets {@code DELIVERED}, which until now was
 * a status nothing produced.
 */
@Service
public class PollDeliveryReceiptsService {

    private static final Logger log = LoggerFactory.getLogger(PollDeliveryReceiptsService.class);

    private final DeliveryRepositoryPort deliveryRepositoryPort;
    private final DeviceRepositoryPort deviceRepositoryPort;
    private final PushNotificationProviderPort pushNotificationProviderPort;
    private final ReceiptProperties properties;

    public PollDeliveryReceiptsService(DeliveryRepositoryPort deliveryRepositoryPort,
                                        DeviceRepositoryPort deviceRepositoryPort,
                                        PushNotificationProviderPort pushNotificationProviderPort,
                                        ReceiptProperties properties) {
        this.deliveryRepositoryPort = deliveryRepositoryPort;
        this.deviceRepositoryPort = deviceRepositoryPort;
        this.pushNotificationProviderPort = pushNotificationProviderPort;
        this.properties = properties;
    }

    /** @return how many deliveries were settled — confirmed or failed. */
    public int run() {
        Instant now = Instant.now();
        List<NotificationDelivery> awaiting = deliveryRepositoryPort.findAwaitingReceipt(
                now.minus(Duration.ofHours(properties.maxAgeHours())),
                now.minus(Duration.ofSeconds(properties.minAgeSeconds())),
                properties.batchLimit());

        if (awaiting.isEmpty()) {
            return 0;
        }

        Map<String, NotificationDelivery> byMessageId = new HashMap<>();
        awaiting.forEach(delivery -> byMessageId.put(delivery.getProviderMessageId(), delivery));

        List<PushReceipt> receipts =
                pushNotificationProviderPort.fetchReceipts(List.copyOf(byMessageId.keySet()));

        int delivered = 0;
        int failed = 0;
        int invalidTokens = 0;

        for (PushReceipt receipt : receipts) {
            NotificationDelivery delivery = byMessageId.get(receipt.providerMessageId());
            if (delivery == null) {
                continue;
            }
            switch (receipt.outcome()) {
                case DELIVERED -> {
                    delivery.markDelivered();
                    delivered++;
                }
                case INVALID_TOKEN -> {
                    delivery.markInvalidToken(receipt.detail());
                    // The whole point of reading receipts: this is where a dead
                    // token usually surfaces, long after the send succeeded.
                    deviceRepositoryPort.disableById(delivery.getDeviceId());
                    invalidTokens++;
                }
                case FAILED -> {
                    delivery.markFailed(receipt.detail());
                    failed++;
                }
                // Not answered yet. Left SENT so the next pass asks again,
                // until it ages out of the window.
                case NOT_READY -> {
                    continue;
                }
            }
            deliveryRepositoryPort.save(delivery);
        }

        log.info("Polled delivery receipts: {} awaiting, {} delivered, {} failed, {} invalid tokens",
                awaiting.size(), delivered, failed, invalidTokens);
        return delivered + failed + invalidTokens;
    }
}
