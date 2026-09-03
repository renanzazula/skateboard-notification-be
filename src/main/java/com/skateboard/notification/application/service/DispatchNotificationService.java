package com.skateboard.notification.application.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skateboard.notification.application.port.out.DeliveryRepositoryPort;
import com.skateboard.notification.application.port.out.DeviceRepositoryPort;
import com.skateboard.notification.application.port.out.PushMessage;
import com.skateboard.notification.application.port.out.PushNotificationProviderPort;
import com.skateboard.notification.application.port.out.PushResult;
import com.skateboard.notification.domain.model.Notification;
import com.skateboard.notification.domain.model.NotificationDelivery;
import com.skateboard.notification.domain.model.NotificationDevice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Sends an already-persisted {@link PreparedDispatch} and records what the
 * provider said about each message.
 *
 * <p>Deliberately not transactional: it makes a network call, and holding a
 * database transaction across that would pin connections for as long as Expo
 * takes to answer. The rows it updates were committed before it ran, so a
 * crash here loses no record of what was owed.
 */
@Service
public class DispatchNotificationService {

    private static final Logger log = LoggerFactory.getLogger(DispatchNotificationService.class);

    public record Result(int devicesTargeted, int sent, int retryable, int failed, int invalidTokens) {

        static Result none() {
            return new Result(0, 0, 0, 0, 0);
        }
    }

    private final DeviceRepositoryPort deviceRepositoryPort;
    private final DeliveryRepositoryPort deliveryRepositoryPort;
    private final PushNotificationProviderPort pushNotificationProviderPort;
    private final ObjectMapper objectMapper;

    public DispatchNotificationService(DeviceRepositoryPort deviceRepositoryPort,
                                        DeliveryRepositoryPort deliveryRepositoryPort,
                                        PushNotificationProviderPort pushNotificationProviderPort,
                                        ObjectMapper objectMapper) {
        this.deviceRepositoryPort = deviceRepositoryPort;
        this.deliveryRepositoryPort = deliveryRepositoryPort;
        this.pushNotificationProviderPort = pushNotificationProviderPort;
        this.objectMapper = objectMapper;
    }

    public Result send(PreparedDispatch prepared) {
        if (prepared.isEmpty()) {
            return Result.none();
        }

        Notification notification = prepared.notification();
        Map<String, String> data = readData(notification);

        List<PushMessage> messages = prepared.devices().stream()
                .map(device -> new PushMessage(device.getPushToken(), notification.getTitle(),
                        notification.getBody(), data))
                .toList();

        prepared.deliveries().forEach(NotificationDelivery::beginAttempt);

        List<PushResult> results;
        try {
            results = pushNotificationProviderPort.send(messages);
        } catch (RuntimeException e) {
            // The provider contract says a failed batch comes back as retryable
            // results, but an unexpected throw must not lose the whole
            // fan-out: every delivery stays PENDING with its attempt counted,
            // so the retry pass owns them from here.
            log.error("notificationId={} provider threw; leaving {} deliveries pending",
                    notification.getId(), prepared.deliveries().size(), e);
            results = List.of();
        }

        return applyResults(prepared, results);
    }

    private Result applyResults(PreparedDispatch prepared, List<PushResult> results) {
        Notification notification = prepared.notification();
        int sent = 0;
        int retryable = 0;
        int failed = 0;
        int invalidTokens = 0;
        List<NotificationDevice> toDisable = new ArrayList<>();

        for (int i = 0; i < prepared.deliveries().size(); i++) {
            NotificationDelivery delivery = prepared.deliveries().get(i);
            NotificationDevice device = prepared.devices().get(i);
            PushResult result = i < results.size()
                    ? results.get(i)
                    : PushResult.retryable("Provider returned no result for this message");

            switch (result.outcome()) {
                case ACCEPTED -> {
                    delivery.markSent(result.providerMessageId());
                    sent++;
                }
                case RETRYABLE -> {
                    delivery.markRetryable(result.detail());
                    retryable++;
                }
                case INVALID_TOKEN -> {
                    delivery.markInvalidToken(result.detail());
                    // The token will never work again, so keeping the device
                    // enabled would burn an attempt on every future
                    // notification (spec §25).
                    toDisable.add(device);
                    invalidTokens++;
                }
                case REJECTED -> {
                    delivery.markFailed(result.detail());
                    failed++;
                }
            }
            deliveryRepositoryPort.save(delivery);
        }

        // Disabled by id rather than by writing back the device snapshot this
        // dispatch loaded: that snapshot predates the send, and re-saving it
        // would revert a device the owner re-registered in the meantime.
        toDisable.forEach(device -> deviceRepositoryPort.disableById(device.getId()));

        log.info("notificationId={} type={} tenantId={} devices={} sent={} retryable={} failed={} invalidTokens={}",
                notification.getId(), notification.getType(), notification.getTenantId(),
                prepared.devices().size(), sent, retryable, failed, invalidTokens);

        return new Result(prepared.devices().size(), sent, retryable, failed, invalidTokens);
    }

    /**
     * The stored payload is opaque to the domain, so it is parsed here at the
     * point it becomes a push. A payload that will not parse costs the deep
     * link, not the notification — the user still gets told, they just land on
     * the app's default screen.
     */
    private Map<String, String> readData(Notification notification) {
        try {
            return objectMapper.readValue(notification.getDataJson(), new TypeReference<Map<String, String>>() {
            });
        } catch (Exception e) {
            log.warn("notificationId={} has unreadable data payload; sending without deep-link metadata",
                    notification.getId(), e);
            return Map.of();
        }
    }
}
