package com.hansul.auth.api;

import java.util.Objects;

/**
 * Stable application user identity derived exclusively from the OIDC {@code sub} claim.
 */
public record AuthenticatedUserId(String value) {

    public AuthenticatedUserId {
        value = Objects.requireNonNull(value, "value").strip();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Authenticated user id must not be blank");
        }
    }
}
