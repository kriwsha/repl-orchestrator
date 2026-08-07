package dev.replorch.api;

import jakarta.validation.constraints.NotBlank;

/**
 * Credential submission payload.
 *
 * <p>Write-only by design: no endpoint ever returns these fields back. Make sure the
 * container does not log request bodies -- see README, "Secrets".
 */
public record CredentialRequest(
        @NotBlank String id,
        @NotBlank String host,
        Integer port,
        @NotBlank String database,
        @NotBlank String username,
        @NotBlank String password,
        String sslmode
) {
    public int portOrDefault() { return port == null ? 5432 : port; }
    public String sslmodeOrDefault() { return (sslmode == null || sslmode.isBlank()) ? "require" : sslmode; }
}
