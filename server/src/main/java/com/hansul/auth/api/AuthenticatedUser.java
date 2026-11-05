package com.hansul.auth.api;

public record AuthenticatedUser(
        AuthenticatedUserId id,
        String username,
        String email
) {
}
