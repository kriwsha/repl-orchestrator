package dev.replorch.domain;

/** One column as reported by {@code pg_attribute} + {@code format_type}. */
public record ColumnDef(String name, String type, int position) {}
