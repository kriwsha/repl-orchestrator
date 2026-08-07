package dev.replorch.provision;

import dev.replorch.creds.CredentialStoreRegistry;
import dev.replorch.db.Db;
import dev.replorch.domain.ReplicationSpec;
import dev.replorch.spi.Credential;
import dev.replorch.util.Ident;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Creates the publication on the source and the subscription on the subscriber, in that order.
 *
 * <p>Ordering matters: the publication must exist before {@code CREATE SUBSCRIPTION} connects,
 * otherwise the subscription is created but never receives anything.
 */
@Service
public class ReplicationProvisioner {

    private static final Logger log = LoggerFactory.getLogger(ReplicationProvisioner.class);
    private static final String APP = "repl-orchestrator/provision";

    private final CredentialStoreRegistry credentials;

    public ReplicationProvisioner(CredentialStoreRegistry credentials) {
        this.credentials = credentials;
    }

    public List<String> provision(ReplicationSpec spec) throws SQLException {
        // Validate every identifier before opening a single connection.
        Ident.check(spec.publicationName(), "publicationName");
        Ident.check(spec.subscriptionName(), "subscriptionName");
        Ident.check(spec.slotName(), "slotName");
        if (spec.tables() == null || spec.tables().isEmpty()) {
            throw new IllegalArgumentException("at least one table is required");
        }
        spec.tables().forEach(Ident::qualified);

        Credential source = credentials.require(spec.sourceCredentialId());
        Credential subscriber = credentials.require(spec.subscriberCredentialId());

        List<String> steps = new ArrayList<>();

        try (Connection src = Db.open(source, APP)) {
            assertTablesExist(src, spec.tables());
            assertReplicaIdentity(src, spec.tables());

            String tableList = spec.tables().stream()
                    .map(Ident::qualified).collect(Collectors.joining(", "));
            String createPub = "CREATE PUBLICATION %s FOR TABLE %s"
                    .formatted(Ident.quote(spec.publicationName()), tableList);
            Db.execute(src, createPub);
            steps.add(createPub);
            log.info("created publication {} on source {}",
                    spec.publicationName(), source.conninfoRedacted());
        }

        try (Connection sub = Db.open(subscriber, APP)) {
            // The password ends up in pg_subscription.subconninfo -- see README, "Secrets".
            String createSub = ("CREATE SUBSCRIPTION %s CONNECTION %s PUBLICATION %s "
                    + "WITH (slot_name = %s, create_slot = true, copy_data = %s, "
                    + "disable_on_error = true)")
                    .formatted(
                            Ident.quote(spec.subscriptionName()),
                            Ident.literal(source.conninfo()),
                            Ident.quote(spec.publicationName()),
                            Ident.literal(spec.slotName()),
                            spec.copyData() ? "true" : "false");

            // CREATE SUBSCRIPTION that creates a slot cannot run inside a transaction block.
            Db.executeAutoCommit(sub, createSub);

            steps.add(createSub.replace(Ident.literal(source.conninfo()),
                    Ident.literal(source.conninfoRedacted())));
            log.info("created subscription {} with disable_on_error=true", spec.subscriptionName());
        }
        return steps;
    }

    /** Drop in reverse order; leaves the slot behind only if the subscriber cannot reach the source. */
    public List<String> deprovision(ReplicationSpec spec) throws SQLException {
        List<String> steps = new ArrayList<>();
        Credential subscriber = credentials.require(spec.subscriberCredentialId());
        try (Connection sub = Db.open(subscriber, APP)) {
            String drop = "DROP SUBSCRIPTION IF EXISTS " + Ident.quote(spec.subscriptionName());
            Db.executeAutoCommit(sub, drop);
            steps.add(drop);
        }
        Credential source = credentials.require(spec.sourceCredentialId());
        try (Connection src = Db.open(source, APP)) {
            String drop = "DROP PUBLICATION IF EXISTS " + Ident.quote(spec.publicationName());
            Db.execute(src, drop);
            steps.add(drop);
        }
        return steps;
    }

    /** Re-point an existing subscription after credential rotation in the store. */
    public String refreshConnection(ReplicationSpec spec) throws SQLException {
        Credential source = credentials.require(spec.sourceCredentialId());
        Credential subscriber = credentials.require(spec.subscriberCredentialId());
        try (Connection sub = Db.open(subscriber, APP)) {
            String sql = "ALTER SUBSCRIPTION %s CONNECTION %s".formatted(
                    Ident.quote(spec.subscriptionName()), Ident.literal(source.conninfo()));
            Db.executeAutoCommit(sub, sql);
            return "ALTER SUBSCRIPTION %s CONNECTION %s".formatted(
                    Ident.quote(spec.subscriptionName()), Ident.literal(source.conninfoRedacted()));
        }
    }

    private void assertTablesExist(Connection src, List<String> tables) throws SQLException {
        String sql = "SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
                + "WHERE n.nspname = ? AND c.relname = ? AND c.relkind IN ('r','p')";
        try (PreparedStatement ps = src.prepareStatement(sql)) {
            for (String t : tables) {
                ps.setString(1, Ident.schemaOf(t));
                ps.setString(2, Ident.tableOf(t));
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new IllegalArgumentException("table " + t + " does not exist on the source");
                    }
                }
            }
        }
    }

    /**
     * A table with REPLICA IDENTITY DEFAULT and no primary key cannot replicate UPDATE/DELETE.
     * Surfacing this at creation time avoids a failure that only appears under write traffic.
     */
    private void assertReplicaIdentity(Connection src, List<String> tables) throws SQLException {
        String sql = "SELECT c.relreplident, "
                + "  EXISTS (SELECT 1 FROM pg_index i WHERE i.indrelid = c.oid AND i.indisprimary) AS has_pk "
                + "FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
                + "WHERE n.nspname = ? AND c.relname = ?";
        try (PreparedStatement ps = src.prepareStatement(sql)) {
            for (String t : tables) {
                ps.setString(1, Ident.schemaOf(t));
                ps.setString(2, Ident.tableOf(t));
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        String ident = rs.getString("relreplident");
                        boolean hasPk = rs.getBoolean("has_pk");
                        if ("d".equals(ident) && !hasPk) {
                            throw new IllegalArgumentException(
                                    "table " + t + " has REPLICA IDENTITY DEFAULT but no primary key; "
                                    + "UPDATE and DELETE will fail to replicate. Add a primary key or "
                                    + "set REPLICA IDENTITY FULL / USING INDEX first.");
                        }
                    }
                }
            }
        }
    }
}
