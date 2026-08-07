package dev.replorch.spi;

import java.sql.Connection;
import java.util.List;

/**
 * Plugin interface for "is this subscription broken, and why?".
 *
 * <p>Detectors are advisory only. The orchestrator never applies DDL because a detector
 * fired -- it applies DDL only when the schema diff independently shows a fixable
 * difference. That keeps a locale-dependent, version-dependent log string from ever
 * being load-bearing.
 */
public interface ApplyErrorDetector {

    String name();

    /**
     * @param subscriberConn   open connection to the subscriber; implementations must not close it
     * @param subscriptionName subscription under inspection
     */
    List<ErrorSignal> detect(Connection subscriberConn, String subscriptionName);

    /** Lower runs first. */
    default int order() { return 100; }
}
