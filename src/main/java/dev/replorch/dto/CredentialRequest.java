package dev.replorch.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Credential submission payload.
 *
 * <p>Write-only by design: no endpoint ever returns these fields back. Make sure the
 * container does not log request bodies -- see README, "Secrets".
 */
public record CredentialRequest(
        @NotBlank String id,
        @NotBlank String jdbcUrl,
        @NotBlank String username,
        @NotBlank String password
) {}
