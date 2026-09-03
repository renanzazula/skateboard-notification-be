package com.skateboard.notification.application.service;

import com.skateboard.notification.domain.model.NotificationType;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationTemplateResolverTest {

    private final NotificationTemplateResolver resolver = new NotificationTemplateResolver();

    @Test
    void rendersTheEpisodeTitleIntoTheBody() {
        NotificationTemplateResolver.Template template = resolver.resolve(
                NotificationType.NEW_PODCAST, Map.of("title", "Barcelona Street Sessions #14"));

        assertThat(template.title()).isEqualTo("New podcast available");
        assertThat(template.body()).isEqualTo("Barcelona Street Sessions #14");
    }

    /**
     * A literal "{{title}}" on someone's lock screen is worse than a terse
     * notification, so an unfilled placeholder is dropped.
     */
    @Test
    void dropsAPlaceholderThatHasNoValue() {
        NotificationTemplateResolver.Template template =
                resolver.resolve(NotificationType.NEW_PODCAST, Map.of());

        assertThat(template.body()).isEmpty();
    }

    @Test
    void treatsANullValueAsEmptyRatherThanPrintingNull() {
        Map<String, String> variables = new HashMap<>();
        variables.put("title", null);

        assertThat(resolver.resolve(NotificationType.NEW_PODCAST, variables).body()).isEmpty();
    }

    @Test
    void rejectsATypeThatHasNoTemplateInsteadOfSendingBlankCopy() {
        assertThatThrownBy(() -> resolver.resolve(NotificationType.NEW_MAGAZINE, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NEW_MAGAZINE");
    }
}
