package com.rateshield.authentication.dto;

public record AuthResponse(
        String message,
        String username,
        String token
) {
    public AuthResponse(String message, String username) {
        this(message, username, null);
    }
}
