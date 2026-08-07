package dev.replorch.monitor;

import dev.replorch.spi.ApplyErrorDetector;
import dev.replorch.spi.ErrorSignal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Corroborating detector: tails the subscriber's server log for the schema-drift message.
 *
 * <p><b>Advisory only, and off by default.</b> It is the weakest signal in the system:
 * <ul>
 *   <li>the wording differs across major versions;</li>
 *   <li>it is translated when {@code lc_messages} is not English;</li>
 *   <li>{@code pg_read_file} needs the {@code pg_read_server_files} role and is unavailable
 *       on RDS, Cloud SQL and most managed platforms;</li>
 *   <li>it needs {@code logging_collector = on}.</li>
 * </ul>
 * It therefore only ever raises confidence on a diagnosis the schema diff has already made.
 * Nothing is applied because this detector fired.
 */
@Component
@ConditionalOnProperty(prefix = "replorch.detector.server-log", name = "enabled", havingValue = "true")
public class ServerLogDetector implements ApplyErrorDetector {

    private static final Logger log = LoggerFactory.getLogger(ServerLogDetector.class);

    /** Matches the English PG wording across versions; intentionally loose. */
    private static final Pattern MISSING_COLUMN =
            Pattern.compile("is missing (replicated )?column", Pattern.CASE_INSENSITIVE);

    private static final int TAIL_BYTES = 64 * 1024;

    @Override
    public String name() { return "server-log"; }

    @Override
    public int order() { return 50; }

    @Override
    public List<ErrorSignal> detect(Connection subscriberConn, String subscriptionName) {
        String sql = "SELECT pg_read_file(pg_current_logfile(), "
                + "GREATEST(0, (pg_stat_file(pg_current_logfile())).size - ?), ?)";
        try (PreparedStatement ps = subscriberConn.prepareStatement(sql)) {
            ps.setInt(1, TAIL_BYTES);
            ps.setInt(2, TAIL_BYTES);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return List.of();
                String tail = rs.getString(1);
                if (tail == null) return List.of();

                for (String line : tail.split("\n")) {
                    if (MISSING_COLUMN.matcher(line).find()) {
                        return List.of(new ErrorSignal(ErrorSignal.Kind.SCHEMA_DRIFT,
                                ErrorSignal.Confidence.CONFIRMED,
                                truncate(line.trim(), 500), name()));
                    }
                }
            }
        } catch (Exception e) {
            // Expected on managed platforms and without pg_read_server_files: degrade quietly.
            log.debug("server-log detector unavailable ({}); relying on state + schema diff",
                    e.getClass().getSimpleName());
        }
        return List.of();
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
