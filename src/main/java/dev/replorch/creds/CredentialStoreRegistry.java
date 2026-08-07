package dev.replorch.creds;

import dev.replorch.spi.Credential;
import dev.replorch.spi.CredentialStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * Resolves scheme-prefixed credential ids against the available stores.
 *
 * <p>Stores come from two places: Spring beans, and {@link ServiceLoader} discovery so a
 * third-party JAR can add a backend (AWS Secrets Manager, CyberArk, Conjur...) without
 * touching this codebase. Spring beans win on scheme collision, since they are the ones
 * that can be configured.
 */
@Service
public class CredentialStoreRegistry {

    private static final Logger log = LoggerFactory.getLogger(CredentialStoreRegistry.class);

    private final Map<String, CredentialStore> stores = new LinkedHashMap<>();

    public CredentialStoreRegistry(List<CredentialStore> springStores) {
        for (CredentialStore s : ServiceLoader.load(CredentialStore.class)) {
            stores.put(s.scheme(), s);
            log.info("credential store discovered via ServiceLoader: {}", s.scheme());
        }
        for (CredentialStore s : springStores) {
            CredentialStore replaced = stores.put(s.scheme(), s);
            if (replaced != null) {
                log.info("credential store '{}' overridden by Spring bean {}",
                        s.scheme(), s.getClass().getSimpleName());
            } else {
                log.info("credential store registered: {}", s.scheme());
            }
        }
    }

    public List<String> schemes() { return List.copyOf(stores.keySet()); }

    /** Resolve a full id such as {@code vault:pg/prod-source}. */
    public Credential require(String qualifiedId) {
        return resolve(qualifiedId).orElseThrow(() -> new IllegalArgumentException(
                "credential '" + qualifiedId + "' not found in store '" + schemeOf(qualifiedId) + "'"));
    }

    public Optional<Credential> resolve(String qualifiedId) {
        return store(schemeOf(qualifiedId)).resolve(idOf(qualifiedId));
    }

    public String store(String qualifiedId, Credential credential) {
        return store(schemeOf(qualifiedId)).store(idOf(qualifiedId), credential);
    }

    public boolean exists(String qualifiedId) {
        return store(schemeOf(qualifiedId)).exists(idOf(qualifiedId));
    }

    private CredentialStore store(String scheme) {
        CredentialStore s = stores.get(scheme);
        if (s == null) {
            throw new IllegalArgumentException(
                    "no credential store for scheme '" + scheme + "'; available: " + stores.keySet());
        }
        return s;
    }

    private static String schemeOf(String qualifiedId) {
        int i = indexOfColon(qualifiedId);
        return qualifiedId.substring(0, i);
    }

    private static String idOf(String qualifiedId) {
        int i = indexOfColon(qualifiedId);
        return qualifiedId.substring(i + 1);
    }

    private static int indexOfColon(String qualifiedId) {
        int i = qualifiedId == null ? -1 : qualifiedId.indexOf(':');
        if (i <= 0 || i == qualifiedId.length() - 1) {
            throw new IllegalArgumentException(
                    "credential id must look like 'scheme:path', got '" + qualifiedId + "'");
        }
        return i;
    }
}
