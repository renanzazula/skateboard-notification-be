package com.skateboard.notification.domain.exception;

public class DeviceNotFoundException extends RuntimeException {

    public DeviceNotFoundException(String deviceIdentifier) {
        super("Device not found: " + deviceIdentifier);
    }
}
