package dev.replorch.api;

import dev.replorch.creds.CredentialService;
import dev.replorch.creds.CredentialStoreRegistry;
import dev.replorch.dto.CredentialRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;


import java.util.Map;

/**
 * Credential intake.
 *
 * <p>There is deliberately no read endpoint for secret material: {@code GET} reports only
 * existence. Anything that can create a replication link can also read from the source
 * database, so this controller must sit behind authentication before it is exposed.
 */
@RestController
@RequestMapping("/api/v1/credentials")
public class CredentialController {

    private final CredentialStoreRegistry registry;
    private final CredentialService credentialService;

    public CredentialController(CredentialStoreRegistry registry, CredentialService credentialService) {
        this.registry = registry;
        this.credentialService = credentialService;
    }

    @GetMapping("/stores")
    public Map<String, Object> stores() {
        return Map.of("schemes", registry.schemes());
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> store(@Valid @RequestBody CredentialRequest req) {
        String id = registry.store(req.id(), credentialService.fromRequest(req));
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("credentialId", id));
    }

    /** Existence check only -- never returns secret material. */
    @GetMapping("/{scheme}/{*path}")
    public Map<String, Object> exists(@PathVariable String scheme, @PathVariable String path) {
        String id = scheme + ":" + path.replaceFirst("^/", "");
        return Map.of("credentialId", id, "exists", registry.exists(id));
    }

}
