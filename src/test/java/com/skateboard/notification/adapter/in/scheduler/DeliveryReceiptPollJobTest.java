package com.skateboard.notification.adapter.in.scheduler;

import com.skateboard.notification.application.service.PollDeliveryReceiptsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.mockito.Mockito.verify;

/**
 * No branching in the job itself — the only thing worth asserting is that
 * the scheduled trigger actually reaches the service it wires to.
 */
class DeliveryReceiptPollJobTest {

    @Mock private PollDeliveryReceiptsService pollDeliveryReceiptsService;

    private DeliveryReceiptPollJob job;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        job = new DeliveryReceiptPollJob(pollDeliveryReceiptsService);
    }

    @Test
    void delegatesToThePollService() {
        job.run();

        verify(pollDeliveryReceiptsService).run();
    }
}
