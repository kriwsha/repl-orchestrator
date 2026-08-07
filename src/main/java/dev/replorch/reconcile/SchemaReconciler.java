package dev.replorch.reconcile;

import dev.replorch.db.Db;
import dev.replorch.domain.ColumnDef;
import dev.replorch.domain.ReplicationSpec;
import dev.replorch.util.Ident;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Compares source and subscriber table structures and heals the safe subset.
 *
 * <p>What is auto-applied and what is not:
 * <ul>
 *   <li><b>column present on source, missing on subscriber, allow-listed built-in type</b> ->
 *       {@code ADD COLUMN} (nullable, no default). This is the change that actually breaks apply.</li>
 *   <li><b>same but a custom/enum/domain type</b> -> proposed only: the type may not exist
 *       on the subscriber, so blindly issuing the DDL would fail or, worse, bind to a
 *       same-named type with different semantics.</li>
 *   <li><b>type mismatch</b> -> proposed only: an in-place {@code ALTER TYPE} can rewrite the
 *       table, fail on existing data, or silently change semantics.</li>
 *   <li><b>column dropped on source</b> -> nothing. The source simply stops sending it; an
 *       extra column on the subscriber is not an error, so there is no reason to drop data.</li>
 * </ul>
 *
 * <p><b>Correctness caveat.</b> Healing restores <i>replication</i>, not necessarily <i>data</i>.
 * If the source added the column {@code WITH DEFAULT}, its historical rows were backfilled
 * locally, and with a fast default that backfill never travelled through WAL. The subscriber's
 * pre-existing rows therefore stay NULL. Streaming resumes; backfilling history is a separate job.
 */
@Service
public class SchemaReconciler {

    private static final Logger log = LoggerFactory.getLogger(SchemaReconciler.class);

    private static final Set<String> DEFAULT_SAFE_TYPES = Set.of(
            "smallint", "integer", "bigint", "boolean", "text", "uuid",
            "numeric", "double precision", "real", "date", "bytea", "inet",
            "timestamp without time zone", "timestamp with time zone",
            "time without time zone", "interval", "json", "jsonb");

    private final Set<String> safeTypes;

    public SchemaReconciler(
            @Value("${replorch.reconcile.safe-types:}") String configured) {
        if (configured == null || configured.isBlank()) {
            this.safeTypes = DEFAULT_SAFE_TYPES;
        } else {
            // Not Set.of(...): it rejects duplicates, so one typo in config would fail startup.
            this.safeTypes = Arrays.stream(configured.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toUnmodifiableSet());
        }
    }

    /**
     * @param apply when false, compute the plan and change nothing
     */
    public List<ReconcileAction> reconcile(ReplicationSpec spec,
                                           Connection source,
                                           Connection subscriber,
                                           boolean apply) throws SQLException {
        List<ReconcileAction> actions = new ArrayList<>();
        int blockingLeft = 0;

        for (String table : spec.tables()) {
            String schema = Ident.schemaOf(table);
            String name = Ident.tableOf(table);

            Map<String, ColumnDef> onSource = columns(source, schema, name);
            Map<String, ColumnDef> onSubscriber = columns(subscriber, schema, name);

            if (onSource.isEmpty()) {
                actions.add(new ReconcileAction(table, ReconcileAction.Kind.ADD_COLUMN, null, false,
                        "table not found on source; skipped"));
                continue;
            }
            if (onSubscriber.isEmpty()) {
                blockingLeft++;
                actions.add(new ReconcileAction(table, ReconcileAction.Kind.ADD_COLUMN, null, false,
                        "table missing on subscriber; create it and run "
                        + "ALTER SUBSCRIPTION ... REFRESH PUBLICATION (out of scope for auto-heal)"));
                continue;
            }

            for (ColumnDef col : onSource.values()) {
                ColumnDef local = onSubscriber.get(col.name());

                if (local == null) {
                    boolean safe = safeTypes.contains(col.type());
                    String sql = "ALTER TABLE %s ADD COLUMN %s %s".formatted(
                            Ident.qualified(table), Ident.quote(col.name()), col.type());

                    if (safe && apply) {
                        Db.execute(subscriber, sql);
                        log.info("healed {}: added column {} {}", table, col.name(), col.type());
                        actions.add(new ReconcileAction(table, ReconcileAction.Kind.ADD_COLUMN,
                                sql, true, "additive, allow-listed type"));
                    } else {
                        if (!safe) blockingLeft++;
                        actions.add(new ReconcileAction(table, ReconcileAction.Kind.ADD_COLUMN,
                                sql, false, safe
                                        ? "additive, allow-listed type (plan only)"
                                        : "type '" + col.type() + "' is not allow-listed; "
                                          + "verify it exists on the subscriber and apply manually"));
                    }
                } else if (!local.type().equals(col.type())) {
                    blockingLeft++;
                    actions.add(new ReconcileAction(table, ReconcileAction.Kind.RETYPE,
                            "ALTER TABLE %s ALTER COLUMN %s TYPE %s".formatted(
                                    Ident.qualified(table), Ident.quote(col.name()), col.type()),
                            false,
                            "type mismatch %s -> %s; never auto-applied (rewrite/semantics risk)"
                                    .formatted(local.type(), col.type())));
                }
            }
        }

        boolean healedSomething = actions.stream().anyMatch(ReconcileAction::applied);
        if (apply && healedSomething && blockingLeft == 0) {
            String sql = "ALTER SUBSCRIPTION %s ENABLE".formatted(Ident.quote(spec.subscriptionName()));
            Db.executeAutoCommit(subscriber, sql);
            log.info("re-enabled subscription {} after clean heal", spec.subscriptionName());
            actions.add(new ReconcileAction(null, ReconcileAction.Kind.ENABLE_SUBSCRIPTION,
                    sql, true, "all drift resolved"));
        } else if (apply && blockingLeft > 0) {
            actions.add(new ReconcileAction(null, ReconcileAction.Kind.ENABLE_SUBSCRIPTION,
                    "ALTER SUBSCRIPTION %s ENABLE".formatted(Ident.quote(spec.subscriptionName())),
                    false, blockingLeft + " item(s) need manual action; subscription left disabled"));
        }
        return actions;
    }

    private Map<String, ColumnDef> columns(Connection conn, String schema, String table)
            throws SQLException {
        String sql = "SELECT a.attname, format_type(a.atttypid, a.atttypmod) AS typ, a.attnum "
                + "FROM pg_attribute a "
                + "JOIN pg_class c ON c.oid = a.attrelid "
                + "JOIN pg_namespace n ON n.oid = c.relnamespace "
                + "WHERE n.nspname = ? AND c.relname = ? AND a.attnum > 0 AND NOT a.attisdropped "
                + "ORDER BY a.attnum";
        Map<String, ColumnDef> out = new LinkedHashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getString("attname"), new ColumnDef(
                            rs.getString("attname"), rs.getString("typ"), rs.getInt("attnum")));
                }
            }
        }
        return out;
    }
}
