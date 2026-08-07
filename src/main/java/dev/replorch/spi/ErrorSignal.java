package dev.replorch.spi;

/**
 * Evidence that a subscription is unhealthy.
 *
 * @param kind       what the detector believes is wrong
 * @param confidence CONFIRMED means an unambiguous cause was observed (e.g. the exact
 *                   server-log line); SUSPECTED means state looks wrong, cause unknown
 * @param detail     human-readable evidence, safe to log (never contains credentials)
 * @param source     which detector produced this
 */
public record ErrorSignal(Kind kind, Confidence confidence, String detail, String source) {

    public enum Kind {
        /** Subscription disabled or apply errors accumulating; cause not established. */
        APPLY_ERROR,
        /** Cause established as schema drift (missing or incompatible column). */
        SCHEMA_DRIFT,
        /** Slot retaining WAL or holding catalog_xmin beyond thresholds. */
        SLOT_UNHEALTHY
    }

    public enum Confidence { SUSPECTED, CONFIRMED }
}
