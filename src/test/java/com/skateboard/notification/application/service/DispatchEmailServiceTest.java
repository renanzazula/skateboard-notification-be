package com.skateboard.notification.application.service;

import com.skateboard.notification.application.port.out.EmailDeliveryRepositoryPort;
import com.skateboard.notification.application.port.out.EmailMessage;
import com.skateboard.notification.application.port.out.EmailProviderPort;
import com.skateboard.notification.application.port.out.EmailResult;
import com.skateboard.notification.domain.model.EmailDelivery;
import com.skateboard.notification.domain.model.EmailDeliveryStatus;
import com.skateboard.notification.domain.model.EmailProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DispatchEmailServiceTest {

    @Mock private EmailDeliveryRepositoryPort emailDeliveryRepositoryPort;
    @Mock private EmailProviderPort emailProviderPort;

    private DispatchEmailService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new DispatchEmailService(emailDeliveryRepositoryPort, emailProviderPort);
        when(emailDeliveryRepositoryPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void doesNothingForAnEmptyList() {
        DispatchEmailService.Result result = service.send(List.of());

        assertThat(result).isEqualTo(new DispatchEmailService.Result(0, 0, 0, 0));
        verifyNoInteractions(emailProviderPort);
    }

    @Test
    void anAcceptedEmailIsMarkedSentWithTheProviderMessageId() {
        EmailDelivery delivery = delivery();
        when(emailProviderPort.send(any())).thenReturn(List.of(EmailResult.accepted("msg-1")));

        DispatchEmailService.Result result = service.send(List.of(delivery));

        assertThat(result).isEqualTo(new DispatchEmailService.Result(1, 1, 0, 0));
        assertThat(delivery.getStatus()).isEqualTo(EmailDeliveryStatus.SENT);
        assertThat(delivery.getProviderMessageId()).isEqualTo("msg-1");
    }

    @Test
    void aRetryableResultLeavesTheDeliveryPending() {
        EmailDelivery delivery = delivery();
        when(emailProviderPort.send(any())).thenReturn(List.of(EmailResult.retryable("timeout")));

        service.send(List.of(delivery));

        assertThat(delivery.getStatus()).isEqualTo(EmailDeliveryStatus.PENDING);
        assertThat(delivery.getFailureReason()).isEqualTo("timeout");
    }

    @Test
    void aRejectedResultMarksTheDeliveryFailed() {
        EmailDelivery delivery = delivery();
        when(emailProviderPort.send(any())).thenReturn(List.of(EmailResult.rejected("bad address")));

        service.send(List.of(delivery));

        assertThat(delivery.getStatus()).isEqualTo(EmailDeliveryStatus.FAILED);
    }

    @Test
    void aProviderThrowingLeavesEveryDeliveryPendingWithTheAttemptCounted() {
        EmailDelivery delivery = delivery();
        when(emailProviderPort.send(any())).thenThrow(new RuntimeException("boom"));

        service.send(List.of(delivery));

        assertThat(delivery.getStatus()).isEqualTo(EmailDeliveryStatus.PENDING);
        assertThat(delivery.getAttemptCount()).isEqualTo(1);
    }

    @Test
    void aShortResultListTreatsTheMissingOnesAsRetryable() {
        EmailDelivery first = delivery();
        EmailDelivery second = delivery();
        when(emailProviderPort.send(any())).thenReturn(List.of(EmailResult.accepted("msg-1")));

        service.send(List.of(first, second));

        assertThat(first.getStatus()).isEqualTo(EmailDeliveryStatus.SENT);
        assertThat(second.getStatus()).isEqualTo(EmailDeliveryStatus.PENDING);
    }

    @Test
    void sendsOneMessagePerDeliveryInOrder() {
        EmailDelivery delivery = delivery();
        when(emailProviderPort.send(any())).thenReturn(List.of(EmailResult.accepted("msg-1")));

        service.send(List.of(delivery));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EmailMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(emailProviderPort).send(captor.capture());
        assertThat(captor.getValue()).singleElement().satisfies(message -> {
            assertThat(message.to()).isEqualTo("jane@example.com");
            assertThat(message.subject()).isEqualTo("subject");
            assertThat(message.body()).isEqualTo("body");
        });
    }

    private EmailDelivery delivery() {
        return EmailDelivery.pending("GUEST_APPLICATION", "app-1", "jane@example.com", "subject", "body",
                EmailProvider.BREVO);
    }
}
