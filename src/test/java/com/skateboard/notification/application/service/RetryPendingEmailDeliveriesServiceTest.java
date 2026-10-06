package com.skateboard.notification.application.service;

import com.skateboard.notification.application.port.out.EmailDeliveryRepositoryPort;
import com.skateboard.notification.domain.model.EmailDelivery;
import com.skateboard.notification.domain.model.EmailDeliveryStatus;
import com.skateboard.notification.domain.model.EmailProvider;
import com.skateboard.notification.infrastructure.email.EmailRetryProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RetryPendingEmailDeliveriesServiceTest {

    @Mock private EmailDeliveryRepositoryPort emailDeliveryRepositoryPort;

    @Test
    void doesNothingWhenThereIsNoBacklog() {
        DispatchEmailService dispatch = mock(DispatchEmailService.class);
        RetryPendingEmailDeliveriesService service = new RetryPendingEmailDeliveriesService(
                emailDeliveryRepositoryPort, dispatch, new EmailRetryProperties(4, 120, 100));
        when(emailDeliveryRepositoryPort.claimRetryable(anyInt(), any(), anyInt())).thenReturn(List.of());

        assertThat(service.run()).isZero();
        verifyNoInteractions(dispatch);
    }

    @Test
    void resendsEveryClaimedDeliveryThroughDispatch() {
        DispatchEmailService dispatch = mock(DispatchEmailService.class);
        RetryPendingEmailDeliveriesService service = new RetryPendingEmailDeliveriesService(
                emailDeliveryRepositoryPort, dispatch, new EmailRetryProperties(4, 120, 100));
        EmailDelivery delivery = delivery();
        when(emailDeliveryRepositoryPort.claimRetryable(anyInt(), any(), anyInt())).thenReturn(List.of(delivery));
        when(dispatch.send(List.of(delivery))).thenReturn(new DispatchEmailService.Result(1, 1, 0, 0));

        int resent = service.run();

        assertThat(resent).isEqualTo(1);
    }

    @Test
    void marksADeliveryFailedOnceItsAttemptBudgetIsExhausted() {
        DispatchEmailService dispatch = mock(DispatchEmailService.class);
        RetryPendingEmailDeliveriesService service = new RetryPendingEmailDeliveriesService(
                emailDeliveryRepositoryPort, dispatch, new EmailRetryProperties(1, 120, 100));
        EmailDelivery delivery = delivery();
        delivery.beginAttempt();
        delivery.markRetryable("still pending");
        when(emailDeliveryRepositoryPort.claimRetryable(anyInt(), any(), anyInt())).thenReturn(List.of(delivery));
        when(dispatch.send(any())).thenReturn(new DispatchEmailService.Result(1, 0, 1, 0));

        service.run();

        assertThat(delivery.getStatus()).isEqualTo(EmailDeliveryStatus.FAILED);
    }

    private EmailDelivery delivery() {
        return EmailDelivery.pending("GUEST_APPLICATION", "app-1", "jane@example.com", "subject", "body",
                EmailProvider.BREVO);
    }
}
