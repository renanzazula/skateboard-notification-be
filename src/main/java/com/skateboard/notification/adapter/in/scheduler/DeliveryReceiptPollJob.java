package com.skateboard.notification.adapter.in.scheduler;

import com.skateboard.notification.application.service.PollDeliveryReceiptsService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Triggers the receipt pass — no logic here, matching the other jobs.
 *
 * <p>No scheduler lock: reading a receipt has no external side effect, so two
 * instances doing it at once is wasteful at worst, never a double send, and the
 * status transitions it drives are idempotent.
 */
@Component
@ConditionalOnProperty(prefix = "push.receipts", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DeliveryReceiptPollJob {

    private final PollDeliveryReceiptsService pollDeliveryReceiptsService;

    public DeliveryReceiptPollJob(PollDeliveryReceiptsService pollDeliveryReceiptsService) {
        this.pollDeliveryReceiptsService = pollDeliveryReceiptsService;
    }

    @Scheduled(cron = "${push.receipts.cron}")
    public void run() {
        pollDeliveryReceiptsService.run();
    }
}
