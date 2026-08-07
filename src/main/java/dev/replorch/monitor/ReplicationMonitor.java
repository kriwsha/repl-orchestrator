package dev.replorch.monitor;

import dev.replorch.creds.CredentialStoreRegistry;
import dev.replorch.db.Db;
import dev.replorch.domain.HealthReport;
import dev.replorch.domain.ReplicationSpec;
import dev.replorch.reconcile.ReconcileAction;
import dev.replorch.reconcile.SchemaReconciler;
import dev.replorch.registry.ReplicationRegistry;
import dev.replorch.spi.ApplyErrorDetector;
import dev.replorch.spi.Credential;
import dev.replorch.spi.ErrorSignal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Periodically inspects every supervised link and, when drift is found, heals it.
 *
 * <p>Control flow, and the reason for it: detectors decide <i>whether to look closer</i>,
 * the schema diff decides <i>what to do</i>. A detector firing is never sufficient to run
 * DDL -- only a concrete, resolvable difference between source and subscriber structures is.
 * That inversion is what keeps a translated or reworded log line from causing a schema change.
 */
@Service
public class ReplicationMonitor {

    private static final Logger log = LoggerFactory.getLogger(ReplicationMonitor.class);
    private static final String APP = "repl-orchestrator/monitor";

    private final ReplicationRegistry registry;
    private final CredentialStoreRegistry credentials;
    private final SchemaReconciler reconciler;
    private final List<ApplyErrorDetector> detectors;

    public ReplicationMonitor(ReplicationRegistry registry,
                              CredentialStoreRegistry credentials,
                              SchemaReconciler reconciler,
                              List<ApplyErrorDetector> detectors) {
        this.registry = registry;
        this.credentials = credentials;
        this.reconciler = reconciler;
        this.detectors = detectors.stream()
                .sorted(Comparator.comparingInt(ApplyErrorDetector::order)).toList();
        log.info("detectors active: {}", this.detectors.stream().map(ApplyErrorDetector::name).toList());
    }

    @Scheduled(fixedDelayString = "${replorch.monitor.interval-ms:30000}",
               initialDelayString = "${replorch.monitor.initial-delay-ms:15000}")
    public void sweep() {
        for (ReplicationSpec spec : registry.all()) {
            try {
                HealthReport report = check(spec);
                if (!report.problems().isEmpty()) {
                    log.warn("replication '{}' unhealthy: {}", spec.name(), report.problems());
                    heal(spec, spec.autoHeal());
                }
            } catch (Exception e) {
                log.error("sweep failed for replication '{}': {}", spec.name(), e.toString());
            }
        }
    }

    public HealthReport check(ReplicationSpec spec) throws SQLException {
        Credential sourceCred = credentials.require(spec.sourceCredentialId());
        Credential subCred = credentials.require(spec.subscriberCredentialId());

        List<ErrorSignal> signals = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        boolean enabled = true;
        long applyErrors = 0;

        try (Connection sub = Db.open(subCred, APP)) {
            for (ApplyErrorDetector d : detectors) {
                signals.addAll(d.detect(sub, spec.subscriptionName()));
            }
            String sql = "SELECT su.subenabled, COALESCE(ss.apply_error_count, 0) AS c "
                    + "FROM pg_subscription su "
                    + "LEFT JOIN pg_stat_subscription_stats ss ON ss.subid = su.oid "
                    + "WHERE su.subname = ?";
            try (PreparedStatement ps = sub.prepareStatement(sql)) {
                ps.setString(1, spec.subscriptionName());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        enabled = rs.getBoolean("subenabled");
                        applyErrors = rs.getLong("c");
                    }
                }
            }
        }

        Long retained = null;
        Boolean slotActive = null;
        try (Connection src = Db.open(sourceCred, APP)) {
            String sql = "SELECT active, "
                    + "pg_wal_lsn_diff(pg_current_wal_lsn(), restart_lsn)::bigint AS retained "
                    + "FROM pg_replication_slots WHERE slot_name = ?";
            try (PreparedStatement ps = src.prepareStatement(sql)) {
                ps.setString(1, spec.slotName());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        slotActive = rs.getBoolean("active");
                        retained = rs.getLong("retained");
                    } else {
                        problems.add("replication slot '" + spec.slotName() + "' is absent on the source");
                    }
                }
            }
        }

        if (!enabled) problems.add("subscription disabled");
        if (applyErrors > 0) problems.add("apply_error_count=" + applyErrors);
        signals.stream()
                .filter(s -> s.confidence() == ErrorSignal.Confidence.CONFIRMED)
                .forEach(s -> problems.add(s.source() + ": " + s.detail()));

        return new HealthReport(spec.name(), Instant.now(), enabled, applyErrors,
                retained, slotActive, signals, problems);
    }

    /**
     * Diff source against subscriber and either apply the safe additive subset or return a plan.
     *
     * @param apply when false, nothing is executed -- the returned list is a proposal
     */
    public List<ReconcileAction> heal(ReplicationSpec spec, boolean apply) throws SQLException {
        Credential sourceCred = credentials.require(spec.sourceCredentialId());
        Credential subCred = credentials.require(spec.subscriberCredentialId());

        try (Connection src = Db.open(sourceCred, APP);
             Connection sub = Db.open(subCred, APP)) {

            List<ReconcileAction> actions = reconciler.reconcile(spec, src, sub, apply);
            if (!apply && !actions.isEmpty()) {
                log.info("replication '{}': {} action(s) proposed (autoHeal off)",
                        spec.name(), actions.size());
            }
            return actions;
        }
    }
}
