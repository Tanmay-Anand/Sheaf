package com.sheaf.domain.ir;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.sheaf.domain.common.Nullable;

import java.util.List;

/**
 * Closed expression set for {@code derive} steps.
 *
 * <p>Covers: arithmetic, ratio/percent, date extraction, bucketing, coalesce, case/when, the
 * restricted {@code sumAll} post-aggregate reference, and (v1.1) a closed set of text functions.
 *
 * <p>Wire format: the "type" discriminator, e.g. {@code {"type":"monthOf","col":"order_date"}}.
 * This generated, schema-exact form is the only wire format; the shorter notation in the docs
 * ({@code {"monthOf":"order_date"}}) is for reading only.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = Expr.ColRef.class,       name = "col"),
        @JsonSubTypes.Type(value = Expr.Lit.class,          name = "lit"),
        @JsonSubTypes.Type(value = Expr.AddExpr.class,      name = "add"),
        @JsonSubTypes.Type(value = Expr.SubExpr.class,      name = "sub"),
        @JsonSubTypes.Type(value = Expr.MulExpr.class,      name = "mul"),
        @JsonSubTypes.Type(value = Expr.DivExpr.class,      name = "div"),
        @JsonSubTypes.Type(value = Expr.RatioExpr.class,    name = "ratio"),
        @JsonSubTypes.Type(value = Expr.PctExpr.class,      name = "pct"),
        @JsonSubTypes.Type(value = Expr.YearOfExpr.class,   name = "yearOf"),
        @JsonSubTypes.Type(value = Expr.QuarterOfExpr.class, name = "quarterOf"),
        @JsonSubTypes.Type(value = Expr.MonthOfExpr.class,  name = "monthOf"),
        @JsonSubTypes.Type(value = Expr.IsoWeekOfExpr.class, name = "isoWeekOf"),
        @JsonSubTypes.Type(value = Expr.DayOfExpr.class,    name = "dayOf"),
        @JsonSubTypes.Type(value = Expr.IsoDayOfWeekExpr.class, name = "isoDayOfWeek"),
        @JsonSubTypes.Type(value = Expr.BucketExpr.class,   name = "bucket"),
        @JsonSubTypes.Type(value = Expr.CoalesceExpr.class, name = "coalesce"),
        @JsonSubTypes.Type(value = Expr.CaseExpr.class,     name = "case"),
        @JsonSubTypes.Type(value = Expr.SumAllExpr.class,   name = "sumAll"),
        @JsonSubTypes.Type(value = Expr.ConcatExpr.class,   name = "concat"),
        @JsonSubTypes.Type(value = Expr.TrimExpr.class,     name = "trim"),
        @JsonSubTypes.Type(value = Expr.UpperExpr.class,    name = "upper"),
        @JsonSubTypes.Type(value = Expr.LowerExpr.class,    name = "lower"),
        @JsonSubTypes.Type(value = Expr.SplitPartExpr.class, name = "splitPart"),
        @JsonSubTypes.Type(value = Expr.ToTextExpr.class,   name = "toText"),
        @JsonSubTypes.Type(value = Expr.ParamRef.class,     name = "param")
})
public sealed interface Expr
        permits Expr.ColRef, Expr.Lit,
                Expr.AddExpr, Expr.SubExpr, Expr.MulExpr, Expr.DivExpr,
                Expr.RatioExpr, Expr.PctExpr,
                Expr.YearOfExpr, Expr.QuarterOfExpr, Expr.MonthOfExpr,
                Expr.IsoWeekOfExpr, Expr.DayOfExpr, Expr.IsoDayOfWeekExpr,
                Expr.BucketExpr, Expr.CoalesceExpr, Expr.CaseExpr, Expr.SumAllExpr,
                Expr.ConcatExpr, Expr.TrimExpr, Expr.UpperExpr, Expr.LowerExpr,
                Expr.SplitPartExpr, Expr.ToTextExpr, Expr.ParamRef {

    // ── Column and literal values ──────────────────────────────────────────────

    @JsonTypeName("col") record ColRef(String col) implements Expr {}
    /**
     * A literal. {@code valueType} says what it is when the JSON alone can't: "2025-01-01" is text
     * unless {@code valueType} is {@code date}.
     */
    @JsonTypeName("lit") record Lit(@Nullable Object value, @Nullable ValueType valueType) implements Expr {}

    /** A run-time parameter declared in the plan's {@code params}. */
    @JsonTypeName("param") record ParamRef(String name) implements Expr {}

    // ── Arithmetic ─────────────────────────────────────────────────────────────

    @JsonTypeName("add")   record AddExpr(Expr a, Expr b)   implements Expr {}
    @JsonTypeName("sub")   record SubExpr(Expr a, Expr b)   implements Expr {}
    @JsonTypeName("mul")   record MulExpr(Expr a, Expr b)   implements Expr {}
    @JsonTypeName("div")   record DivExpr(Expr a, Expr b)   implements Expr {}
    @JsonTypeName("ratio") record RatioExpr(Expr numerator, Expr denominator) implements Expr {}
    @JsonTypeName("pct")   record PctExpr(Expr numerator, Expr denominator)   implements Expr {}

    // ── Date extraction ────────────────────────────────────────────────────────

    @JsonTypeName("yearOf")    record YearOfExpr(String col)    implements Expr {}
    @JsonTypeName("quarterOf") record QuarterOfExpr(String col) implements Expr {}
    @JsonTypeName("monthOf")   record MonthOfExpr(String col)   implements Expr {}
    /** ISO 8601 week (1–53, weeks start Monday). Not Excel's WEEKNUM default; named to say so. */
    @JsonTypeName("isoWeekOf") record IsoWeekOfExpr(String col)    implements Expr {}
    @JsonTypeName("dayOf")     record DayOfExpr(String col)     implements Expr {}
    /** ISO weekday, Monday = 1 … Sunday = 7. Not Excel's WEEKDAY default; named to say so. */
    @JsonTypeName("isoDayOfWeek") record IsoDayOfWeekExpr(String col) implements Expr {}

    // ── Bucketing / coalesce / case ────────────────────────────────────────────

    @JsonTypeName("bucket")
    record BucketExpr(String col, List<Double> breaks, List<String> labels) implements Expr {}

    @JsonTypeName("coalesce")
    record CoalesceExpr(List<Expr> args) implements Expr {}

    @JsonTypeName("case")
    record CaseExpr(
            List<WhenClause> when,
            @JsonProperty("else") Expr else_
    ) implements Expr {}

    /** Restricted grand-total reference. Only valid in a derive immediately following aggregate. */
    @JsonTypeName("sumAll")
    record SumAllExpr(String col) implements Expr {}

    // ── Text (v1.1) ────────────────────────────────────────────────────────────

    /**
     * Joins parts into one string. A null part counts as empty; with {@code skipNulls}, null and
     * blank parts are dropped together with their separator. The result is null only when every
     * part is null.
     */
    @JsonTypeName("concat")
    record ConcatExpr(List<Expr> parts, @Nullable String sep, @Nullable Boolean skipNulls) implements Expr {}

    @JsonTypeName("trim")  record TrimExpr(Expr arg)  implements Expr {}
    @JsonTypeName("upper") record UpperExpr(Expr arg) implements Expr {}
    @JsonTypeName("lower") record LowerExpr(Expr arg) implements Expr {}

    /** The {@code index}-th (1-based) piece of {@code arg} split on the literal {@code sep}; null if absent. */
    @JsonTypeName("splitPart")
    record SplitPartExpr(Expr arg, String sep, Integer index) implements Expr {}

    /**
     * Formats a number or date as text using one of a fixed whitelist of patterns. Only valid inside
     * a {@code concat}, or as a column written to a text-formatted template column: anywhere else it
     * would produce numbers stored as text. Write the typed value and let the number format render it.
     */
    @JsonTypeName("toText")
    record ToTextExpr(Expr arg, String pattern) implements Expr {}

    // ── Supporting types ───────────────────────────────────────────────────────

    record WhenClause(
            @JsonProperty("if") Predicate if_,
            Predicate.ValueRef then
    ) {}
}
