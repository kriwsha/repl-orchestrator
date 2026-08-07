package dev.replorch.reconcile;

/**
 * One planned or executed change on the subscriber.
 *
 * @param table   qualified table
 * @param kind    ADD_COLUMN | RETYPE | ENABLE_SUBSCRIPTION
 * @param sql     the statement (executed, or proposed when {@code applied} is false)
 * @param applied whether it was actually run
 * @param note    why it was or was not applied
 */
public record ReconcileAction(String table, Kind kind, String sql, boolean applied, String note) {
    public enum Kind { ADD_COLUMN, RETYPE, ENABLE_SUBSCRIPTION }
}
