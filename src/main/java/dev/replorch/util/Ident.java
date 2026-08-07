package dev.replorch.util;

import java.util.regex.Pattern;

/**
 * SQL identifier handling.
 *
 * <p>DDL statements such as {@code CREATE PUBLICATION} cannot take bind parameters for
 * object names, so every identifier that reaches a statement goes through here. Two layers:
 * a conservative allow-list check (rejects anything exotic outright) and proper quoting
 * (doubling embedded quotes) for what passes.
 */
public final class Ident {

    /** Deliberately stricter than PostgreSQL: letters, digits, underscore; must not start with a digit. */
    private static final Pattern SAFE = Pattern.compile("^[A-Za-z_][A-Za-z0-9_$]{0,62}$");

    private Ident() {}

    public static String check(String raw, String what) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(what + " must not be blank");
        }
        if (!SAFE.matcher(raw).matches()) {
            throw new IllegalArgumentException(
                    what + " '" + raw + "' is not an accepted identifier "
                    + "(letters, digits, underscore; max 63 chars; must not start with a digit)");
        }
        return raw;
    }

    /** Quote an already-checked identifier. */
    public static String quote(String raw) {
        return "\"" + raw.replace("\"", "\"\"") + "\"";
    }

    public static String checkAndQuote(String raw, String what) {
        return quote(check(raw, what));
    }

    /** Split and validate a {@code schema.table} reference, returning the quoted form. */
    public static String qualified(String schemaDotTable) {
        String[] parts = schemaDotTable.split("\\.", -1);
        if (parts.length != 2) {
            throw new IllegalArgumentException(
                    "table reference '" + schemaDotTable + "' must be schema.table");
        }
        return checkAndQuote(parts[0], "schema") + "." + checkAndQuote(parts[1], "table");
    }

    public static String schemaOf(String schemaDotTable) {
        String[] p = schemaDotTable.split("\\.", -1);
        if (p.length != 2) throw new IllegalArgumentException("expected schema.table: " + schemaDotTable);
        return check(p[0], "schema");
    }

    public static String tableOf(String schemaDotTable) {
        String[] p = schemaDotTable.split("\\.", -1);
        if (p.length != 2) throw new IllegalArgumentException("expected schema.table: " + schemaDotTable);
        return check(p[1], "table");
    }

    /** Single-quote a string literal for contexts that cannot bind (e.g. CONNECTION '...'). */
    public static String literal(String raw) {
        return "'" + raw.replace("'", "''") + "'";
    }
}
