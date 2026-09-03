package com.skateboard.notification.application.service;

import com.skateboard.notification.domain.model.NotificationType;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Turns a notification type plus its variables into the text a user sees.
 *
 * <p>It exists so that "New podcast available" is written once. Scattered
 * across event handlers, copy changes become a grep and a guess (spec §22).
 * Java configuration rather than a database: nothing here is meant to be
 * editable by an administrator yet, and a template engine that nobody can
 * change is just a slower constant.
 */
@Service
public class NotificationTemplateResolver {

    /** Resolved copy for one notification. */
    public record Template(String title, String body) {
    }

    private record Definition(String title, String bodyTemplate) {
    }

    private static final Map<NotificationType, Definition> DEFINITIONS = Map.of(
            NotificationType.NEW_PODCAST,
            new Definition("New podcast available", "{{title}}")
    );

    /**
     * @param variables values substituted into {{placeholders}}; a placeholder
     *                  with no value is dropped rather than rendered literally,
     *                  because "{{title}}" on a lock screen is worse than a
     *                  short body
     */
    public Template resolve(NotificationType type, Map<String, String> variables) {
        Definition definition = DEFINITIONS.get(type);
        if (definition == null) {
            throw new IllegalArgumentException("No notification template for type " + type);
        }
        return new Template(definition.title(), render(definition.bodyTemplate(), variables));
    }

    private String render(String template, Map<String, String> variables) {
        String rendered = template;
        for (Map.Entry<String, String> variable : variables.entrySet()) {
            String value = variable.getValue() == null ? "" : variable.getValue();
            rendered = rendered.replace("{{" + variable.getKey() + "}}", value);
        }
        // Anything still unresolved had no value supplied.
        return rendered.replaceAll("\\{\\{[^}]*}}", "").trim();
    }
}
