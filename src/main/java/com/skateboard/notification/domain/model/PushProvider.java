package com.skateboard.notification.domain.model;

/**
 * Which external service actually delivers the push. Expo is the only one
 * today; it fans out to APNs and FCM itself, which is why neither appears
 * here. A future direct FCM integration adds a constant rather than changing
 * anything the domain does with it.
 */
public enum PushProvider {
    EXPO
}
