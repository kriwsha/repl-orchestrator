package dev.replorch.spi;

import java.util.List;
import java.util.stream.Collectors;

/**
 * A resolved database credential. Never log or serialise this type: {@link #toString()}
 * is deliberately redacted and it is never returned through the REST API.
 */
public record Credential(
        String jdbcUrl,
        List<HostPort> hosts,
        String database,
        String username,
        String password,
        String sslmode,
        String targetServerType
) {
    private static final String DEFAULT_SSLMODE = "require";

    public Credential {
        hosts = List.copyOf(hosts);
    }

    private String ssl() {
        return (sslmode == null || sslmode.isBlank()) ? DEFAULT_SSLMODE : sslmode;
    }

    public String host() {
        return hosts.stream().map(HostPort::host).collect(Collectors.joining(","));
    }

    public int port() {
        return hosts.isEmpty() ? 5432 : hosts.getFirst().port();
    }

    /** libpq conninfo for CREATE SUBSCRIPTION. Contains the password -- see README. */
    public String conninfo() {
        return "host=%s port=%s dbname=%s user=%s password=%s sslmode=%s%s".formatted(
                host(), ports(), database, username, password, ssl(), targetServerTypePart());
    }

    /** Same as {@link #conninfo()} but masked, for logs and error messages. */
    public String conninfoRedacted() {
        return "host=%s port=%s dbname=%s user=%s password=**** sslmode=%s%s".formatted(
                host(), ports(), database, username, ssl(), targetServerTypePart());
    }

    private String ports() {
        return hosts.stream()
                .map(hostPort -> Integer.toString(hostPort.port()))
                .collect(Collectors.joining(","));
    }

    private String targetServerTypePart() {
        return (targetServerType == null || targetServerType.isBlank())
                ? ""
                : " target_session_attrs=" + targetServerType;
    }

    @Override
    public String toString() {
        return "Credential[" + conninfoRedacted() + "]";
    }

    public record HostPort(String host, int port) {}
}
