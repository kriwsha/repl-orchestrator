package dev.replorch.creds;

import dev.replorch.spi.Credential;
import dev.replorch.spi.CredentialStore;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Development-only store. Secrets live in heap and are lost on restart, which is the point:
 * it must never be the production backend. Enable a real store (Vault) for anything else.
 */
@Component
public class InMemoryCredentialStore implements CredentialStore {

    private static final String SCHEMA = "memory";

    private final Map<String, Credential> creds = new ConcurrentHashMap<>();

    @Override
    public String scheme() { return SCHEMA; }

    @Override
    public Optional<Credential> resolve(String id) {
        return Optional.ofNullable(creds.get(id));
    }

    @Override
    public String store(String id, Credential credential) {
        creds.put(id, credential);
        return scheme() + ":" + id;
    }

    @Override
    public boolean exists(String id) { return creds.containsKey(id); }
}
