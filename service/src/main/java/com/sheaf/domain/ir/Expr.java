package com.sheaf.domain.ir;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;

import java.util.List;

/**
 * Closed expression set for {@code derive} steps.
 *
 * <p>Covers: arithmetic, ratio/percent, date extraction, bucketing,
 * coalesce, case/when, and the restricted {@code sumAll} post-aggregate reference.
 *
 * <p>Serialisation note: the "type" discriminator produces JSON like
 * {@code {"type":"monthOf","col":"order_date"}}. The spec's shorter
 * {@code {"monthOf":"order_date"}} format is added via custom serialisers in M3.
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
        @JsonSubTypes.Type(value = Expr.WeekOfExpr.class,   name = "weekOf"),
        @JsonSubTypes.Type(value = Expr.DayOfExpr.class,    name = "dayOf"),
        @JsonSubTypes.Type(value = Expr.DayOfWeekExpr.class, name = "dayOfWeek"),
        @JsonSubTypes.Type(value = Expr.BucketExpr.class,   name = "bucket"),
        @JsonSubTypes.Type(value = Expr.CoalesceExpr.class, name = "coalesce"),
        @JsonSubTypes.Type(value = Expr.CaseExpr.class,     name = "case"),
        @JsonSubTypes.Type(value = Expr.SumAllExpr.class,   name = "sumAll")
})
public sealed interface Expr
        permits Expr.ColRef, Expr.Lit,
                Expr.AddExpr, Expr.SubExpr, Expr.MulExpr, Expr.DivExpr,
                Expr.RatioExpr, Expr.PctExpr,
                Expr.YearOfExpr, Expr.QuarterOfExpr, Expr.MonthOfExpr,
                Expr.WeekOfExpr, Expr.DayOfExpr, Expr.DayOfWeekExpr,
                Expr.BucketExpr, Expr.CoalesceExpr, Expr.CaseExpr, Expr.SumAllExpr {

    // ── Column and literal values ──────────────────────────────────────────────

    @JsonTypeName("col") record ColRef(String col) implements Expr {}
    @JsonTypeName("lit") record Lit(Object value)  implements Expr {}

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
    @JsonTypeName("weekOf")    record WeekOfExpr(String col)    implements Expr {}
    @JsonTypeName("dayOf")     record DayOfExpr(String col)     implements Expr {}
    @JsonTypeName("dayOfWeek") record DayOfWeekExpr(String col) implements Expr {}

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

    // ── Supporting types ───────────────────────────────────────────────────────

    record WhenClause(
            @JsonProperty("if") Predicate if_,
            Predicate.ValueRef then
    ) {}
}
