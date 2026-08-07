package dev.replorch.domain;

import java.util.List;

/**
 * Everything needed to create and then supervise one logical replication link.
 *
 * @param name                  orchestrator-local identifier for this link
 * @param sourceCredentialId    id in a credential store, e.g. {@code vault:pg/prod-source}
 * @param subscriberCredentialId id in a credential store
 * @param publicationName       publication to create on the source
 * @param subscriptionName      subscription to create on the subscriber
 * @param slotName              replication slot name on the source
 * @param tables                {@code schema.table} entries to publish
 * @param copyData              perform initial data copy
 * @param autoHeal              apply safe additive DDL on the subscriber when drift is found;
 *                              when false the orchestrator only reports a plan
 */
public record ReplicationSpec(
        String name,
        String sourceCredentialId,
        String subscriberCredentialId,
        String publicationName,
        String subscriptionName,
        String slotName,
        List<String> tables,
        boolean copyData,
        boolean autoHeal
) {}
