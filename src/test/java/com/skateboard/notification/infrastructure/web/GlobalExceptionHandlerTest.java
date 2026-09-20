package com.skateboard.notification.infrastructure.web;

import com.skateboard.notification.domain.exception.DeviceNotFoundException;
import com.skateboard.notification.infrastructure.web.dto.ErrorResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Every handler here decides the status and message a client actually sees,
 * so each mapping is worth pinning down rather than trusting it works
 * because it compiles.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void accessDeniedBecomesA403WithAFixedMessage() {
        ResponseEntity<ErrorResponse> response = handler.handleAccessDenied(new AccessDeniedException("nope"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertBody(response, HttpStatus.FORBIDDEN, "Access denied");
    }

    @Test
    void deviceNotFoundBecomesA404CarryingTheExceptionMessage() {
        ResponseEntity<ErrorResponse> response =
                handler.handleDeviceNotFound(new DeviceNotFoundException("install-1"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertBody(response, HttpStatus.NOT_FOUND, "Device not found: install-1");
    }

    @Test
    void illegalArgumentBecomesA400CarryingTheExceptionMessage() {
        ResponseEntity<ErrorResponse> response =
                handler.handleBadRequest(new IllegalArgumentException("bad input"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertBody(response, HttpStatus.BAD_REQUEST, "bad input");
    }

    @Test
    void typeMismatchBecomesA400NamingTheOffendingParameter() {
        MethodArgumentTypeMismatchException ex = mock(MethodArgumentTypeMismatchException.class);
        when(ex.getName()).thenReturn("podcastId");

        ResponseEntity<ErrorResponse> response = handler.handleTypeMismatch(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertBody(response, HttpStatus.BAD_REQUEST, "Invalid value for parameter 'podcastId'");
    }

    @Test
    void validationFailureBecomesA400NamingTheFirstFieldError() {
        FieldError fieldError = new FieldError("request", "title", "must not be blank");
        BindingResult bindingResult = mock(BindingResult.class);
        when(bindingResult.getFieldErrors()).thenReturn(List.of(fieldError));
        MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
        when(ex.getBindingResult()).thenReturn(bindingResult);

        ResponseEntity<ErrorResponse> response = handler.handleValidation(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertBody(response, HttpStatus.BAD_REQUEST, "title must not be blank");
    }

    @Test
    void validationFailureWithNoFieldErrorsFallsBackToAGenericMessage() {
        BindingResult bindingResult = mock(BindingResult.class);
        when(bindingResult.getFieldErrors()).thenReturn(List.of());
        MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
        when(ex.getBindingResult()).thenReturn(bindingResult);

        ResponseEntity<ErrorResponse> response = handler.handleValidation(ex);

        assertBody(response, HttpStatus.BAD_REQUEST, "Invalid request");
    }

    @Test
    void anythingElseBecomesA500WithNoLeakedDetail() {
        ResponseEntity<ErrorResponse> response = handler.handleGeneric(new RuntimeException("db connection reset"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertBody(response, HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");
    }

    private void assertBody(ResponseEntity<ErrorResponse> response, HttpStatus status, String message) {
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo(status.value());
        assertThat(response.getBody().getError()).isEqualTo(status.getReasonPhrase());
        assertThat(response.getBody().getMessage()).isEqualTo(message);
        assertThat(response.getBody().getTimestamp()).isNotNull();
    }
}
