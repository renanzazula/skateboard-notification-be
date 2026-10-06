package com.skateboard.notification.application.port.out;

import java.util.List;
import java.util.UUID;

/**
 * Resolves Keycloak user ids to the contact details needed to email them —
 * skateboard-user-be's {@code GET /admin/users/lookup}, the same boundary
 * skateboard-app-config-be's {@code GuestApplicationConfig} javadoc
 * describes: recipient ids are stored as opaque ids there, and this is where
 * they become addresses.
 */
public interface RecipientDirectoryPort {

    record ResolvedRecipient(UUID id, String email, boolean emailVerified, boolean active) {

        /** Whether this identity is safe to email at all. */
        public boolean isEmailable() {
            return active && emailVerified && email != null && !email.isBlank();
        }
    }

    /**
     * @return resolved identities, possibly fewer than {@code userIds} (an id
     *         with no matching identity is silently omitted — see the
     *         endpoint's own contract) or empty entirely if user-be could not
     *         be reached; never throws
     */
    List<ResolvedRecipient> resolve(List<UUID> userIds);
}
