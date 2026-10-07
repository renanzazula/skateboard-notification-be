package com.skateboard.notification.application.service;

import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.Mustache;
import com.github.mustachejava.MustacheFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.Map;

/**
 * Renders an {@code EmailTemplate} subject/body's {@code {{variable}}}
 * placeholders, replacing the old manual
 * {@code template.replace("{name}", ...)}. HTML-escaping is turned off —
 * Brevo is sent {@code textContent} only (see {@code BrevoEmailProvider}),
 * so escaping names like {@code O'Brien} into {@code O&#39;Brien} would
 * corrupt plain-text output rather than protect HTML.
 */
@Component
public class EmailTemplateRenderer {

    private final MustacheFactory mustacheFactory = new DefaultMustacheFactory() {
        @Override
        public void encode(String value, Writer writer) {
            try {
                writer.write(value);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    };

    public String render(String template, Map<String, Object> variables) {
        if (template == null) {
            return null;
        }
        Mustache mustache = mustacheFactory.compile(new StringReader(template), "email-template");
        StringWriter writer = new StringWriter();
        mustache.execute(writer, variables);
        return writer.toString();
    }
}
