package dev.replorch.monitor;

import dev.replorch.spi.ApplyErrorDetector;
import dev.replorch.spi.ErrorSignal;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Primary detector: subscription state and the apply error counter.
 *
 * <p>This is deliberately the load-bearing one. It relies on catalog state rather than log
 * text, so it is immune to message wording changes, {@code lc_messages}, and to managed
 * platforms that do not expose log files. With {@code disable_on_error = true} the failure
 * mode is a clean latch ({@code subenabled = false}) rather than a flapping retry loop --
 * which is also why "the slot went inactive" is a poor trigger: the slot flaps as the apply
 * worker restarts, while this state does not.
 */
@Component
public class SubscriptionStateDetector implements ApplyErrorDetector {

    @Override
    public String name() { return "subscription-state"; }

    @Override
    public int order() { return 10; }

    @Override
    public List<ErrorSignal> detect(Connection subscriberConn, String subscriptionName) {
        List<ErrorSignal> signals = new ArrayList<>();
        String sql = "SELECT su.subenabled, COALESCE(ss.apply_error_count, 0) AS apply_error_count "
                + "FROM pg_subscription su "
                + "LEFT JOIN pg_stat_subscription_stats ss ON ss.subid = su.oid "
                + "WHERE su.subname = ?";
        try (PreparedStatement ps = subscriberConn.prepareStatement(sql)) {
            ps.setString(1, subscriptionName);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    signals.add(new ErrorSignal(ErrorSignal.Kind.APPLY_ERROR,
                            ErrorSignal.Confidence.CONFIRMED,
                            "subscription '" + subscriptionName + "' does not exist on the subscriber",
                            name()));
                    return signals;
                }
                boolean enabled = rs.getBoolean("subenabled");
                long errors = rs.getLong("apply_error_count");

                if (!enabled) {
                    signals.add(new ErrorSignal(ErrorSignal.Kind.APPLY_ERROR,
                            ErrorSignal.Confidence.SUSPECTED,
                            "subscription is disabled (disable_on_error latch or manual)", name()));
                }
                if (errors > 0) {
                    signals.add(new ErrorSignal(ErrorSignal.Kind.APPLY_ERROR,
                            ErrorSignal.Confidence.SUSPECTED,
                            "apply_error_count=" + errors, name()));
                }
            }
        } catch (SQLException e) {
            signals.add(new ErrorSignal(ErrorSignal.Kind.APPLY_ERROR,
                    ErrorSignal.Confidence.SUSPECTED,
                    "state query failed: " + e.getSQLState(), name()));
        }
        return signals;
    }
}
