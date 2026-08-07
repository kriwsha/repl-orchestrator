package dev.replorch.db;

import dev.replorch.spi.Credential;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

/**
 * Short-lived JDBC connections opened per task.
 *
 * <p>Deliberately not pooled: targets are discovered dynamically and credentials are
 * re-resolved from the credential store on every use, so rotation in Vault takes effect
 * on the next operation instead of being pinned by a warm pool.
 */
public final class Db {

    private Db() {}

    public static Connection open(Credential c, String applicationName) throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", c.username());
        props.setProperty("password", c.password());
        props.setProperty("ApplicationName", applicationName);
        // Fail fast rather than hanging a scheduler thread on an unreachable host.
        props.setProperty("connectTimeout", "10");
        props.setProperty("socketTimeout", "60");
        return DriverManager.getConnection(c.jdbcUrl(), props);
    }

    /**
     * Execute DDL that PostgreSQL forbids inside a transaction block
     * (notably {@code CREATE SUBSCRIPTION} when it creates the slot).
     */
    public static void executeAutoCommit(Connection conn, String sql) throws SQLException {
        boolean previous = conn.getAutoCommit();
        if (!previous) conn.setAutoCommit(true);
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        } finally {
            if (!previous) conn.setAutoCommit(previous);
        }
    }

    public static void execute(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    public static boolean queryBoolean(Connection conn, String sql, boolean fallback) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getBoolean(1) : fallback;
        }
    }
}
