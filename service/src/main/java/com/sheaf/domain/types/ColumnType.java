package com.sheaf.domain.types;

import com.sheaf.domain.common.Nullable;

/**
 * A named, typed column in a {@link TableType}.
 *
 * @param cardinality      Known or statically bounded number of distinct values (monthOf ≤ 12), or null.
 * @param origin           The entity column this column still is (survives renames, moves and
 *                         passthrough projects), or null for a computed column. Join validation and
 *                         edit impact analysis resolve against it.
 * @param formula          The source column holds formulas (edit plans warn before overwriting it).
 * @param mayContainErrors Some cells show Excel errors; aggregating them needs the user's consent.
 * @param numbersAsText    Some numbers are stored as text, which Excel's SUM silently skips.
 */
public record ColumnType(
        String name,
        ScalarType type,
        boolean nullable,
        @Nullable Integer cardinality,
        @Nullable Origin origin,
        boolean formula,
        boolean mayContainErrors,
        boolean numbersAsText
) {
    /** Where a column came from: entity and column name as declared in the environment. */
    public record Origin(String entity, String column) {}

    public static ColumnType computed(String name, ScalarType type, boolean nullable) {
        return new ColumnType(name, type, nullable, null, null, false, false, false);
    }

    public static ColumnType computed(String name, ScalarType type, boolean nullable, @Nullable Integer cardinality,
                                      @Nullable Origin origin) {
        return new ColumnType(name, type, nullable, cardinality, origin, false, false, false);
    }

    public ColumnType withName(String newName) {
        return new ColumnType(newName, type, nullable, cardinality, origin, formula, mayContainErrors, numbersAsText);
    }

    public ColumnType withNullable(boolean n) {
        return new ColumnType(name, type, n, cardinality, origin, formula, mayContainErrors, numbersAsText);
    }

    /** "revenue: currency:INR" or "prior_revenue: currency:INR?" for nullable columns. */
    public String render() {
        return name + ": " + type.render() + (nullable ? "?" : "");
    }
}
