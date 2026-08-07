package dev.replorch.spi;

/**
 * A resolved database credential. Never log or serialise this type: {@link #toString()}
 * is deliberately redacted and it is never returned through the REST API.
 */
public record Credential(
        String host,
        int port,
        String database,
        String username,
        String password,
        String sslmode
) {
    private String ssl() {
        return (sslmode == null || sslmode.isBlank()) ? "prefer" : sslmode;
    }

    public String jdbcUrl() {
        return "jdbc:postgresql://%s:%d/%s?sslmode=%s".formatted(host, port, database, ssl());
    }

    /** libpq conninfo for CREATE SUBSCRIPTION. Contains the password -- see README. */
    public String conninfo() {
        return "host=%s port=%d dbname=%s user=%s password=%s sslmode=%s".formatted(
                host, port, database, username, password, ssl());
    }

    /** Same as {@link #conninfo()} but masked, for logs and error messages. */
    public String conninfoRedacted() {
        return "host=%s port=%d dbname=%s user=%s password=**** sslmode=%s".formatted(
                host, port, database, username, ssl());
    }

    @Override
    public String toString() {
        return "Credential[" + conninfoRedacted() + "]";
    }
}
