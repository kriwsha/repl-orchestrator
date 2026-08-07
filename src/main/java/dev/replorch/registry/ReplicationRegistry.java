package dev.replorch.registry;

import dev.replorch.domain.ReplicationSpec;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory catalogue of supervised replication links.
 *
 * <p>Intentionally simple. For production this should be backed by the orchestrator's own
 * database so supervision survives a restart -- swap the map for a repository; nothing else
 * in the codebase depends on the storage choice.
 */
@Component
public class ReplicationRegistry {

    private final Map<String, ReplicationSpec> specs = new ConcurrentHashMap<>();

    public void put(ReplicationSpec spec) { specs.put(spec.name(), spec); }

    public Optional<ReplicationSpec> get(String name) {
        return Optional.ofNullable(specs.get(name));
    }

    public Collection<ReplicationSpec> all() { return specs.values(); }

    public boolean remove(String name) { return specs.remove(name) != null; }
}
