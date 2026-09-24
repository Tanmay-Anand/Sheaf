package com.sheaf.domain.ir;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.sheaf.domain.common.Nullable;

import java.util.List;

/**
 * An operator in the IR pipeline. Steps are applied in order to the source entity's table type.
 *
 * <p>v1.2 operators: filter · derive · aggregate · sort · limit · join · lookup · pivot ·
 * periodCompare · project. Every proposed addition requires a written design rationale.
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
        @JsonSubTypes.Type(value = Step.PeriodCompareStep.class, name = "periodCompare"),
        @JsonSubTypes.Type(value = Step.ProjectStep.class,       name = "project"),
        @JsonSubTypes.Type(value = Step.LookupStep.class,        name = "lookup")
})
public sealed interface Step
        permits Step.FilterStep, Step.DeriveStep, Step.AggregateStep, Step.SortStep,
                Step.LimitStep, Step.JoinStep, Step.PivotStep, Step.PeriodCompareStep,
                Step.ProjectStep, Step.LookupStep {

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
    record LimitStep(Integer n) implements Step {}

    /**
     * Combines tables along an approved join edge. On a non-unique key it multiplies rows; for
     * "bring in a value from another table" use {@link LookupStep}.
     */
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
            @Nullable List<String> groupBy
    ) implements Step {}

    /**
     * Selects, renames, reorders and computes columns in one step. The output is exactly the
     * listed columns, in order: the shape a template (or any fixed layout) demands.
     */
    @JsonTypeName("project")
    record ProjectStep(List<ProjectColumn> columns) implements Step {}

    /**
     * Many-to-one lookup (XLOOKUP): for each row, the matching row of {@code with} by a key that
     * must be unique there. Never multiplies rows; unmatched rows get nulls.
     *
     * @param take Columns of {@code with} to bring in; absent means all except the key.
     */
    @JsonTypeName("lookup")
    record LookupStep(String with, JoinKey on, @Nullable List<String> take) implements Step {}

    // ── Supporting types ───────────────────────────────────────────────────────

    /**
     * A single measure within an aggregate step.
     *
     * @param fn    Aggregate function.
     * @param of    Column name, or "*" for count(*) / countIf.
     * @param where Optional row predicate: the measure only counts or sums matching rows.
     *              Required for countIf.
     * @param as    Output column name.
     */
    record AggregateMeasure(AggFn fn, String of, @Nullable Predicate where, String as) {}

    enum AggFn { count, countDistinct, countIf, sum, avg, min, max }

    /** A sort key: column name and direction. */
    record SortKey(String col, SortDir dir) {}

    enum SortDir { asc, desc }

    /** The join key columns: one from each side of the join. */
    record JoinKey(String left, String right) {}

    enum JoinKind { inner, left }

    enum Grain { day, week, month, quarter, year }

    /** One output column of a project step. */
    record ProjectColumn(String as, Expr expr) {}
}
