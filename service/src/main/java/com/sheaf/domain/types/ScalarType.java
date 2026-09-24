package com.sheaf.domain.types;

import com.sheaf.domain.common.Nullable;

import java.util.List;

/**
 * The scalar type lattice (ir-spec §5.1). Nullability lives on {@link ColumnType}, not here.
 */
public sealed interface ScalarType
        permits ScalarType.NumberType, ScalarType.CurrencyType, ScalarType.PercentType,
                ScalarType.DateType, ScalarType.DateTimeType, ScalarType.StringType,
                ScalarType.BooleanType, ScalarType.CategoricalType {

    /** How the type is written in diagnostics and golden files: {@code currency:INR}, {@code categorical}. */
    String render();

    record NumberType() implements ScalarType {
        public String render() { return "number"; }
    }

    /** @param unit ISO 4217 code; null when the unit is not yet confirmed. */
    record CurrencyType(@Nullable String unit) implements ScalarType {
        public String render() { return unit == null ? "currency" : "currency:" + unit; }
    }

    record PercentType() implements ScalarType {
        public String render() { return "percent"; }
    }

    record DateType() implements ScalarType {
        public String render() { return "date"; }
    }

    record DateTimeType() implements ScalarType {
        public String render() { return "datetime"; }
    }

    record StringType() implements ScalarType {
        public String render() { return "string"; }
    }

    record BooleanType() implements ScalarType {
        public String render() { return "boolean"; }
    }

    /**
     * A finite domain.
     *
     * @param domain   Identifier of the domain (usually the column name).
     * @param members  Known members (in declared order when {@code ordering} is {@code declared}),
     *                 or null when only the cardinality is known.
     * @param ordering Whether the values have an order, and whether it is declared.
     */
    record CategoricalType(String domain, @Nullable List<String> members, Ordering ordering) implements ScalarType {
        public String render() { return "categorical"; }

        public boolean ordered() { return ordering == Ordering.declared; }
    }

    /**
     * {@code none}: nominal values (regions, names); sorting is alphabetical, lt/gt are refused.
     * {@code declared}: ordinal with a declared order (low &lt; medium &lt; high); sort and lt/gt use it.
     * {@code missing}: ordinal, but the order is not declared yet; sorting is refused rather than
     * silently alphabetical (which would put "high" before "low").
     */
    enum Ordering { none, declared, missing }

    NumberType NUMBER = new NumberType();
    PercentType PERCENT = new PercentType();
    DateType DATE = new DateType();
    DateTimeType DATETIME = new DateTimeType();
    StringType STRING = new StringType();
    BooleanType BOOLEAN = new BooleanType();

    static boolean isNumeric(ScalarType t) {
        return t instanceof NumberType || t instanceof CurrencyType || t instanceof PercentType;
    }

    static boolean isTemporal(ScalarType t) {
        return t instanceof DateType || t instanceof DateTimeType;
    }

    static boolean isText(ScalarType t) {
        return t instanceof StringType || t instanceof CategoricalType;
    }

    /** Types that support lt/lte/gt/gte and min/max. */
    static boolean isOrdered(ScalarType t) {
        return isNumeric(t) || isTemporal(t) || (t instanceof CategoricalType c && c.ordered());
    }
}
