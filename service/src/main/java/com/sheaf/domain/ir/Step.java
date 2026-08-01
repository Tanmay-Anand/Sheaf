package com.sheaf.domain.ir;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;

import java.util.List;

/**
 * An operator in the IR pipeline. Steps are applied in order to the source entity's table type.
 *
 * <p>v1 operators: filter · derive · aggregate · sort · limit · join · pivot · periodCompare.
 * Every proposed addition after v1 requires a written rationale in {@code docs/ir-decisions.md}.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "op")
@JsonSubTypes({
        @JsonSubTypes.Type(value = Step.FilterStep.class,        name = "filter"),
        @JsonSubTypes.Type(value = Step.DeriveStep.class,        name = "derive"),
        @JsonSubTypes.Type(value = Step.AggregateStep.class,     name = "aggregate"),
        @JsonSubTypes.Type(value = Step.SortStep.class,          name = "sort"),
        @JsonSubTypes.Type(value = Step.LimitStep.class,         name = "limit"),
        @JsonSubTypes.Type(value = Step.JoinStep.class,          name = "join"),
        @JsonSubTypes.Type(value = Step.PivotStep.class,         name = "pivot"),
        @JsonSubTypes.Type(value = Step.PeriodCompareStep.class, name = "periodCompare")
})
public sealed interface Step
        permits Step.FilterStep, Step.DeriveStep, Step.AggregateStep, Step.SortStep,
                Step.LimitStep, Step.JoinStep, Step.PivotStep, Step.PeriodCompareStep {

    // ── Operators ──────────────────────────────────────────────────────────────

    /** Selects rows. Shape-preserving: output schema == input schema. */
    @JsonTypeName("filter")
    record FilterStep(Predicate predicate) implements Step {}

    /** Adds a single computed column. Shape-extending. */
    @JsonTypeName("derive")
    record DeriveStep(String as, Expr expr) implements Step {}

    /** Groups and reduces. Output columns = groupBy columns + declared measures. */
    @JsonTypeName("aggregate")
    record AggregateStep(
            List<String> groupBy,
            List<AggregateMeasure> measures
    ) implements Step {}

    /** Reorders rows. Shape-preserving. */
    @JsonTypeName("sort")
    record SortStep(List<SortKey> by) implements Step {}

    /** Takes the first n rows. Should follow a sort step. */
    @JsonTypeName("limit")
    record LimitStep(int n) implements Step {}

    /** Combines tables along an approved join edge. */
    @JsonTypeName("join")
    record JoinStep(String with, JoinKey on, JoinKind kind) implements Step {}

    /** Reshapes long to wide. Must be the terminal step; column count is data-dependent. */
    @JsonTypeName("pivot")
    record PivotStep(List<String> rows, String cols, String values) implements Step {}

    /** First-class period-over-period comparison. Not sugar over filter + aggregate + join. */
    @JsonTypeName("periodCompare")
    record PeriodCompareStep(
            String metric,
            String timeDim,
            Grain grain,
            String current,
            String prior,
            List<String> groupBy
    ) implements Step {}

    // ── Supporting types ───────────────────────────────────────────────────────

    /**
     * A single measure within an aggregate step.
     *
     * @param fn      Aggregate function name (sum, avg, count, countDistinct, countIf, min, max).
     * @param of      Column name, or "*" for count(*).
     * @param where   Optional conditional predicate (countIf / sumIf pattern).
     * @param as      Output column name.
     */
    record AggregateMeasure(String fn, String of, Predicate where, String as) {}

    /** A sort key: column name and direction. */
    record SortKey(String col, String dir) {}

    /** The join key columns: one from each side of the join. */
    record JoinKey(String left, String right) {}

    enum JoinKind { inner, left }

    enum Grain { day, week, month, quarter, year }
}
