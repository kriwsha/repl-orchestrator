package dev.replorch.creds;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.replorch.spi.Credential;
import dev.replorch.spi.CredentialStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * HashiCorp Vault KV v2 backend, spoken over plain HTTP to avoid pulling in Spring Cloud Vault.
 *
 * <p>Ids are the KV path relative to the mount, e.g. {@code vault:pg/prod-source} reads
 * {@code {addr}/v1/{mount}/data/pg/prod-source}. The token is supplied by configuration
 * (in Kubernetes, prefer a projected service-account token refreshed out of band).
 */
@Component
@ConditionalOnProperty(prefix = "replorch.vault", name = "enabled", havingValue = "true")
public class VaultCredentialStore implements CredentialStore {

    private static final Logger log = LoggerFactory.getLogger(VaultCredentialStore.class);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    private final CredentialService credentialService;
    private final String address;
    private final String mount;
    private final String token;

    public VaultCredentialStore(
            CredentialService credentialService,
            @Value("${replorch.vault.address}") String address,
            @Value("${replorch.vault.mount:secret}") String mount,
            @Value("${replorch.vault.token}") String token) {
        this.credentialService = credentialService;
        this.address = address.replaceAll("/+$", "");
        this.mount = mount;
        this.token = token;
    }

    @Override
    public String scheme() { return "vault"; }

    @Override
    public Optional<Credential> resolve(String id) {
        String url = "%s/v1/%s/data/%s".formatted(address, mount, id);
        try {
            HttpResponse<String> res = http.send(
                    HttpRequest.newBuilder(URI.create(url))
                            .header("X-Vault-Token", token)
                            .timeout(Duration.ofSeconds(10))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            if (res.statusCode() == 404) return Optional.empty();
            if (res.statusCode() != 200) {
                // Never echo the body: Vault errors can quote request content.
                log.warn("vault read of '{}' failed with status {}", id, res.statusCode());
                return Optional.empty();
            }
            JsonNode data = mapper.readTree(res.body()).path("data").path("data");
            if (data.isMissingNode()) return Optional.empty();

            if (data.hasNonNull("jdbcUrl") && !data.path("jdbcUrl").asText().isBlank()) {
                return Optional.of(credentialService.fromRequest(new dev.replorch.dto.CredentialRequest(
                        id,
                        data.path("jdbcUrl").asText(),
                        data.path("username").asText(),
                        data.path("password").asText())));
            }

            List<Credential.HostPort> hosts = List.of(new Credential.HostPort(
                    data.path("host").asText(),
                    data.path("port").asInt(5432)));
            return Optional.of(new Credential(
                    buildJdbcUrl(hosts, data.path("database").asText(), data.path("sslmode").asText("require")),
                    hosts,
                    data.path("database").asText(),
                    data.path("username").asText(),
                    data.path("password").asText(),
                    data.path("sslmode").asText("require"),
                    null));
        } catch (Exception e) {
            log.warn("vault read of '{}' failed: {}", id, e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private String buildJdbcUrl(List<Credential.HostPort> hosts, String database, String sslmode) {
        String hostPart = hosts.stream()
                .map(hostPort -> hostPort.host() + ":" + hostPort.port())
                .reduce((left, right) -> left + "," + right)
                .orElseThrow();
        return "jdbc:postgresql://%s/%s?sslmode=%s".formatted(hostPart, database, sslmode);
    }

    @Override
    public String store(String id, Credential c) {
        String url = "%s/v1/%s/data/%s".formatted(address, mount, id);
        try {
            String body = mapper.writeValueAsString(Map.of("data", Map.of(
                    "jdbcUrl", c.jdbcUrl(),
                    "username", c.username(), "password", c.password())));

            HttpResponse<String> res = http.send(
                    HttpRequest.newBuilder(URI.create(url))
                            .header("X-Vault-Token", token)
                            .header("Content-Type", "application/json")
                            .timeout(Duration.ofSeconds(10))
                            .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());

            if (res.statusCode() / 100 != 2) {
                throw new IllegalStateException("vault write failed with status " + res.statusCode());
            }
            return scheme() + ":" + id;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("vault write failed: " + e.getClass().getSimpleName(), e);
        }
    }
}
