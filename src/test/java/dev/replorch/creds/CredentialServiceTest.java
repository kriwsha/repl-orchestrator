package dev.replorch.creds;

import dev.replorch.dto.CredentialRequest;
import dev.replorch.spi.Credential;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CredentialServiceTest {

    private final CredentialService service = new CredentialService();

    @Test
    void parsesFullJdbcUrlWithMultipleHosts() {
        Credential credential = service.fromRequest(new CredentialRequest(
                "memory:src-1",
                "jdbc:postgresql://pg-primary:5432,pg-standby:5433/app?targetServerType=primary&sslmode=verify-full",
                "repl",
                "secret"));

        assertThat(credential.jdbcUrl())
                .isEqualTo("jdbc:postgresql://pg-primary:5432,pg-standby:5433/app?targetServerType=primary&sslmode=verify-full");
        assertThat(credential.hosts())
                .containsExactly(
                        new Credential.HostPort("pg-primary", 5432),
                        new Credential.HostPort("pg-standby", 5433));
        assertThat(credential.database()).isEqualTo("app");
        assertThat(credential.sslmode()).isEqualTo("verify-full");
        assertThat(credential.targetServerType()).isEqualTo("primary");
        assertThat(credential.conninfo())
                .isEqualTo("host=pg-primary,pg-standby port=5432,5433 dbname=app user=repl "
                        + "password=secret sslmode=verify-full target_session_attrs=primary");
    }

    @Test
    void appliesDefaultsForMissingPortAndSslmode() {
        Credential credential = service.fromRequest(new CredentialRequest(
                "memory:src-1",
                "jdbc:postgresql://pg-source/app",
                "repl",
                "secret"));

        assertThat(credential.hosts()).containsExactly(new Credential.HostPort("pg-source", 5432));
        assertThat(credential.sslmode()).isEqualTo("require");
        assertThat(credential.conninfo())
                .isEqualTo("host=pg-source port=5432 dbname=app user=repl password=secret sslmode=require");
    }

    @Test
    void rejectsNonPostgresqlJdbcUrls() {
        assertThatThrownBy(() -> service.fromRequest(new CredentialRequest(
                "memory:src-1",
                "jdbc:mysql://db/app",
                "repl",
                "secret")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jdbcUrl must start with jdbc:postgresql://");
    }
}
