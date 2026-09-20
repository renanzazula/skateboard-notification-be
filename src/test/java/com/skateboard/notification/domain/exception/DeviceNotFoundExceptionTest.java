package com.skateboard.notification.domain.exception;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DeviceNotFoundExceptionTest {

    @Test
    void messageNamesTheMissingDeviceIdentifier() {
        DeviceNotFoundException exception = new DeviceNotFoundException("install-42");

        assertThat(exception.getMessage()).isEqualTo("Device not found: install-42");
    }
}
