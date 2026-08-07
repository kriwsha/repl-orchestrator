package dev.replorch.api;

import dev.replorch.domain.ReplicationSpec;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/** Everything needed to stand up one replication link. */
public record ReplicationRequest(
        @NotBlank String name,
        @NotBlank String sourceCredentialId,
        @NotBlank String subscriberCredentialId,
        @NotBlank String publicationName,
        @NotBlank String subscriptionName,
        String slotName,
        @NotEmpty List<String> tables,
        Boolean copyData,
        Boolean autoHeal
) {
    public ReplicationSpec toSpec() {
        return new ReplicationSpec(
                name, sourceCredentialId, subscriberCredentialId,
                publicationName, subscriptionName,
                (slotName == null || slotName.isBlank()) ? subscriptionName : slotName,
                List.copyOf(tables),
                copyData == null || copyData,
                // Auto-applying DDL is opt-in: default to plan-only.
                autoHeal != null && autoHeal);
    }
}
