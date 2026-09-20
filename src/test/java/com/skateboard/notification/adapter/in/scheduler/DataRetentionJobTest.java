package com.skateboard.notification.adapter.in.scheduler;

import com.skateboard.notification.application.service.PurgeExpiredDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.mockito.Mockito.verify;

/**
 * No branching in the job itself — the only thing worth asserting is that
 * the scheduled trigger actually reaches the service it wires to.
 */
class DataRetentionJobTest {

    @Mock private PurgeExpiredDataService purgeExpiredDataService;

    private DataRetentionJob job;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        job = new DataRetentionJob(purgeExpiredDataService);
    }

    @Test
    void delegatesToThePurgeService() {
        job.run();

        verify(purgeExpiredDataService).run();
    }
}
