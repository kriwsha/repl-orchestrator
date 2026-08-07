package dev.replorch.spi;

import java.util.Optional;

/**
 * Plugin interface for credential backends.
 *
 * <p>Implementations are discovered at startup in two ways:
 * <ul>
 *   <li>as Spring beans, or</li>
 *   <li>via {@link java.util.ServiceLoader} -- drop a JAR on the classpath with a
 *       {@code META-INF/services/dev.replorch.spi.CredentialStore} entry and it is picked up
 *       with no code change here.</li>
 * </ul>
 *
 * <p>Credential ids are namespaced by scheme: {@code vault:pg/prod-source},
 * {@code memory:src-1}. The prefix before the first colon selects the store.
 */
public interface CredentialStore {

    /** Scheme this store answers to, e.g. {@code vault}. Must be unique across plugins. */
    String scheme();

    /** Resolve a credential by its id (the part after {@code scheme:}). */
    Optional<Credential> resolve(String id);

    /**
     * Persist a credential and return its full id including scheme. Read-only backends
     * should throw {@link UnsupportedOperationException}; the API surfaces that as 405.
     */
    default String store(String id, Credential credential) {
        throw new UnsupportedOperationException("credential store '" + scheme() + "' is read-only");
    }

    /** Whether an id exists, without materialising the secret. */
    default boolean exists(String id) {
        return resolve(id).isPresent();
    }
}
