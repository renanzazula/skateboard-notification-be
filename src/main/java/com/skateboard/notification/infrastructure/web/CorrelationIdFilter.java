package com.skateboard.notification.infrastructure.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Every request gets a correlation id: skateboard-ui-backend's
 * {@code X-Correlation-Id} if it forwarded one (see that service's
 * CorrelationIdFilter/CorrelationIdExchangeFilter, which relay the id from
 * the original frontend request), otherwise a generated one. It's put in MDC
 * for logging ({@code %X{correlationId}} in application.yml's log pattern)
 * and echoed back on the response so the same id ties together log lines
 * across every service that handled one logical request.
 * <p>
 * Ordered ahead of Spring Security's filter chain (registered by Boot at
 * {@code HIGHEST_PRECEDENCE}) so the correlation id is already in MDC for
 * any log line emitted while handling a rejected-before-reaching-a-controller
 * request (e.g. a missing/invalid token).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String correlationId = request.getHeader(HEADER);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }

        MDC.put(MDC_KEY, correlationId);
        response.setHeader(HEADER, correlationId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
