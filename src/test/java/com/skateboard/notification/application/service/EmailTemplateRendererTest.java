package com.skateboard.notification.application.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EmailTemplateRendererTest {

    private final EmailTemplateRenderer renderer = new EmailTemplateRenderer();

    @Test
    void substitutesAVariable() {
        String result = renderer.render("Hi {{name}}, welcome!", Map.of("name", "Jane"));

        assertThat(result).isEqualTo("Hi Jane, welcome!");
    }

    @Test
    void substitutesMultipleVariables() {
        String result = renderer.render("{{name}} ({{email}}): {{message}}",
                Map.of("name", "Jane", "email", "jane@example.com", "message", "I love skating"));

        assertThat(result).isEqualTo("Jane (jane@example.com): I love skating");
    }

    @Test
    void doesNotHtmlEscapeTheValue() {
        // Output is Brevo textContent, not HTML — escaping would corrupt it.
        String result = renderer.render("Hi {{name}}", Map.of("name", "O'Brien & Sons"));

        assertThat(result).isEqualTo("Hi O'Brien & Sons");
    }

    @Test
    void returnsNullForANullTemplate() {
        assertThat(renderer.render(null, Map.of())).isNull();
    }

    @Test
    void leavesAnUnmatchedVariableBlank() {
        String result = renderer.render("Hi {{name}}, {{missing}}!", Map.of("name", "Jane"));

        assertThat(result).isEqualTo("Hi Jane, !");
    }
}
