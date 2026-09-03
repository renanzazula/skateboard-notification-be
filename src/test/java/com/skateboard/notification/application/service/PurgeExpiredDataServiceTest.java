package com.skateboard.notification.application.service;

import com.skateboard.notification.application.port.out.RetentionRepositoryPort;
import com.skateboard.notification.infrastructure.retention.RetentionProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * The loop that keeps a retention pass from either holding a delete lock over a
 * large range or leaving a backlog untouched.
 */
class PurgeExpiredDataServiceTest {

    private static final int BATCH = 500;
    private static final int MAX_BATCHES = 20;

    @Mock private RetentionRepositoryPort retentionRepositoryPort;

    private PurgeExpiredDataService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new PurgeExpiredDataService(retentionRepositoryPort,
                new RetentionProperties(true, 90, 7, BATCH, MAX_BATCHES));
    }

    @Test
    void keepsDeletingUntilABatchComesBackShort() {
        when(retentionRepositoryPort.deleteNotificationsOlderThan(any(), eq(BATCH)))
                .thenReturn(BATCH, BATCH, 137);
        when(retentionRepositoryPort.deleteProcessedEventsOlderThan(any(), eq(BATCH)))
                .thenReturn(0);

        PurgeExpiredDataService.PurgeReport report = service.run();

        assertThat(report.notificationsDeleted()).isEqualTo(BATCH + BATCH + 137);
        assertThat(report.processedEventsDeleted()).isZero();
        verify(retentionRepositoryPort, times(3)).deleteNotificationsOlderThan(any(), eq(BATCH));
        verify(retentionRepositoryPort, times(1)).deleteProcessedEventsOlderThan(any(), eq(BATCH));
        verifyNoMoreInteractions(retentionRepositoryPort);
    }

    @Test
    void stopsAtThePerRunBatchCapWhenTheBacklogIsHuge() {
        when(retentionRepositoryPort.deleteNotificationsOlderThan(any(), eq(BATCH))).thenReturn(BATCH);
        when(retentionRepositoryPort.deleteProcessedEventsOlderThan(any(), eq(BATCH))).thenReturn(BATCH);

        service.run();

        verify(retentionRepositoryPort, times(MAX_BATCHES)).deleteNotificationsOlderThan(any(), eq(BATCH));
        verify(retentionRepositoryPort, times(MAX_BATCHES)).deleteProcessedEventsOlderThan(any(), eq(BATCH));
    }

    @Test
    void derivesEachCutoffFromItsConfiguredTtl() {
        when(retentionRepositoryPort.deleteNotificationsOlderThan(any(), eq(BATCH))).thenReturn(0);
        when(retentionRepositoryPort.deleteProcessedEventsOlderThan(any(), eq(BATCH))).thenReturn(0);
        Instant before = Instant.now();

        service.run();

        Instant after = Instant.now();

        ArgumentCaptor<Instant> notificationCutoff = ArgumentCaptor.forClass(Instant.class);
        verify(retentionRepositoryPort).deleteNotificationsOlderThan(notificationCutoff.capture(), eq(BATCH));
        assertThat(notificationCutoff.getValue())
                .isBetween(before.minus(Duration.ofDays(90)), after.minus(Duration.ofDays(90)));

        ArgumentCaptor<Instant> processedEventCutoff = ArgumentCaptor.forClass(Instant.class);
        verify(retentionRepositoryPort).deleteProcessedEventsOlderThan(processedEventCutoff.capture(), eq(BATCH));
        assertThat(processedEventCutoff.getValue())
                .isBetween(before.minus(Duration.ofDays(7)), after.minus(Duration.ofDays(7)));
    }
}
