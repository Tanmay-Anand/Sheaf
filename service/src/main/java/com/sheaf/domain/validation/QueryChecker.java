package com.sheaf.domain.validation;

import com.sheaf.domain.common.Nullable;
import com.sheaf.domain.ir.ParamDecl;
import com.sheaf.domain.ir.Plan;
import com.sheaf.domain.ir.Sink;
import com.sheaf.domain.ir.Step;
import com.sheaf.domain.ir.ValueType;
import com.sheaf.domain.types.ColumnType;
import com.sheaf.domain.types.ScalarType;
import com.sheaf.domain.types.ScalarType.CategoricalType;
import com.sheaf.domain.types.ScalarType.CurrencyType;
import com.sheaf.domain.types.TableType;
import com.sheaf.domain.types.TypeEnvironment;
import com.sheaf.domain.validation.ExprTyper.Typed;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static com.sheaf.domain.validation.DiagnosticCode.*;

/**
 * Type-checks a bound query plan: a left fold over the pipeline (ir-spec §5.3). Checking stops
 * after the first step with an error, because every later step's input type depends on it.
 */
final class QueryChecker {

    static final int PIVOT_CARDINALITY_LIMIT = 50;
    static final int SHEET_NAME_MAX = 31;

    private static final Pattern CELL = Pattern.compile("^\\$?[A-Za-z]{1,3}\\$?[1-9]\\d{0,6}$");
    private static final Pattern SHEET_NAME_FORBIDDEN = Pattern.compile("[\\[\\]:*?/\\\\]");
    private static final Map<Step.Grain, Pattern> PERIOD = Map.of(
            Step.Grain.year, Pattern.compile("^\\d{4}$"),
            Step.Grain.quarter, Pattern.compile("^\\d{4}-Q[1-4]$"),
            Step.Grain.month, Pattern.compile("^\\d{4}-(0[1-9]|1[0-2])$"),
            Step.Grain.week, Pattern.compile("^\\d{4}-W(0[1-9]|[1-4]\\d|5[0-3])$"),
            Step.Grain.day, Pattern.compile("^\\d{4}-(0[1-9]|1[0-2])-(0[1-9]|[12]\\d|3[01])$"));

    private final TypeEnvironment env;
    private final Diags d = new Diags();
    /** Template columns a project step deliberately leaves empty (a null literal), by key. */
    private final Set<String> unmapped = new HashSet<>();
    /** Output columns holding toText results outside a concat (numbers or dates as text): key → pointer of the expression. */
    private final Map<String, String> formatted = new LinkedHashMap<>();
    private Map<String, ValueType> params = Map.of();

    private QueryChecker(TypeEnvironment env) {
        this.env = env;
    }

    static CheckResult check(Plan.QueryPlan plan, TypeEnvironment env) {
        return new QueryChecker(env).run(plan);
    }

    private CheckResult run(Plan.QueryPlan plan) {
        params = declareParams(plan.params(), d);
        d.at(null, "source", "");
        var entity = env.entity(plan.source());
        if (entity.isEmpty()) {
            var names = env.entities().keySet();
            d.add(E_UNKNOWN_SOURCE, "source",
                    "'" + plan.source() + "' is not a known table." + Diags.didYouMean(plan.source(), names),
                    "Known tables: " + Diags.list(names.stream().sorted().toList()) + ".",
                    "received", plan.source(), "candidates", Diags.candidates(plan.source(), names));
            return new CheckResult(null, d.all(), null);
        }
        String sourceName = entity.get().name();
        TableType table = entity.get().tableType();
        Set<String> eliminated = new HashSet<>();
        Step previous = null;
        boolean onlyFiltersSoFar = true;

        for (int i = 0; i < plan.steps().size(); i++) {
            Step step = plan.steps().get(i);
            d.at(i, operator(step), "/steps/" + i);
            if (previous instanceof Step.PivotStep) {
                d.add(E_PIVOT_NOT_TERMINAL, null, "No step may follow a pivot; its output columns are only known at run time.",
                        "Move the pivot to the end of the pipeline, or drop the steps after it.");
                return new CheckResult(null, d.all(), null);
            }
            var aggregateBefore = previous instanceof Step.AggregateStep a ? a : null;
            TableType next = step(step, table, eliminated, aggregateBefore, previous, onlyFiltersSoFar, sourceName, "/steps/" + i);
            if (next == null || d.stepFailed()) return new CheckResult(null, d.all(), null);
            for (String n : table.names()) if (!next.has(n)) eliminated.add(TableType.key(n));
            next.names().forEach(n -> eliminated.remove(TableType.key(n)));
            formatted.keySet().removeIf(k -> !next.has(k));
            table = next;
            onlyFiltersSoFar &= step instanceof Step.FilterStep;
            previous = step;
        }

        d.at(null, "sink", "/sink");
        sink(plan.sink(), table, plan.steps().isEmpty(), sourceName);
        return new CheckResult(table, d.all(), null);
    }

    /** Declared parameters by key; duplicate names are an error. Shared with the edit checker. */
    static Map<String, ValueType> declareParams(List<ParamDecl> decls, Diags d) {
        Map<String, ValueType> out = new LinkedHashMap<>();
        for (int i = 0; i < decls.size(); i++) {
            var p = decls.get(i);
            d.at(null, "params", "/params/" + i);
            if (out.putIfAbsent(TableType.key(p.name()), p.valueType()) != null) {
                d.add(E_INVALID_ARGUMENT, "name", "The parameter '" + p.name() + "' is declared twice.",
                        "Declare each parameter once.", "received", p.name());
            }
        }
        return out;
    }

    static String operator(Step s) {
        return switch (s) {
            case Step.FilterStep f -> "filter";
            case Step.DeriveStep f -> "derive";
            case Step.AggregateStep f -> "aggregate";
            case Step.SortStep f -> "sort";
            case Step.LimitStep f -> "limit";
            case Step.JoinStep f -> "join";
            case Step.LookupStep f -> "lookup";
            case Step.PivotStep f -> "pivot";
            case Step.PeriodCompareStep f -> "periodCompare";
            case Step.ProjectStep f -> "project";
        };
    }

    private @Nullable TableType step(Step step, TableType t, Set<String> eliminated, @Nullable Step.AggregateStep aggregateBefore,
                                     @Nullable Step previous, boolean onlyFiltersSoFar, String sourceName, String base) {
        var x = new ExprTyper(t, eliminated, aggregateBefore, params, d);
        return switch (step) {
            case Step.FilterStep f -> {
                x.predicate(f.predicate(), "predicate");
                yield t;
            }
            case Step.DeriveStep s -> derive(s, t, x);
            case Step.AggregateStep a -> aggregate(a, t, x);
            case Step.SortStep s -> sort(s, t, x);
            case Step.LimitStep l -> limit(l, t, previous);
            case Step.JoinStep j -> join(j, t, x, sourceName);
            case Step.LookupStep l -> lookup(l, t, x, sourceName);
            case Step.PivotStep p -> pivot(p, t, x);
            case Step.PeriodCompareStep p -> periodCompare(p, t, x, onlyFiltersSoFar, sourceName);
            case Step.ProjectStep p -> project(p, x, base);
        };
    }

    /** toText outside a concat can only go into a text-formatted template column; never into a computed column. */
    static boolean refuseFormatted(Typed e, Diags d, String field) {
        if (!e.formatted()) return false;
        d.add(E_TOTEXT_OUTSIDE_CONCAT, field,
                "toText here would store a number or date as text, which breaks sums and sorting downstream.",
                "Keep the typed value and let the number format show it; use toText only inside a concat.");
        return true;
    }

    private @Nullable TableType derive(Step.DeriveStep s, TableType t, ExprTyper x) {
        if (t.has(s.as())) {
            d.add(E_DERIVE_COLUMN_COLLISION, "as", "A column named '" + s.as() + "' already exists (names ignore case, as in Excel).",
                    "Choose a new name for the derived column, e.g. '" + s.as() + "_2'.", "received", s.as());
            return null;
        }
        Typed e = x.expr(s.expr(), "expr");
        if (e == null || refuseFormatted(e, d, "expr")) return null;
        return t.plus(ColumnType.computed(s.as(), typeOrString(e), e.nullable(), e.cardinality(), null));
    }

    private @Nullable TableType aggregate(Step.AggregateStep a, TableType t, ExprTyper x) {
        List<ColumnType> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < a.groupBy().size(); i++) {
            String g = a.groupBy().get(i);
            var c = t.find(g);
            if (c.isEmpty()) {
                d.add(E_AGGREGATE_GROUPBY_UNKNOWN, "groupBy[" + i + "]", "Column '" + g + "' does not exist." + Diags.didYouMean(g, t.names()),
                        "Available columns to group by: " + Diags.list(t.names()) + ".", "received", g,
                        "candidates", Diags.candidates(g, t.names()));
                continue;
            }
            if (!seen.add(TableType.key(g))) continue;
            out.add(c.get());
        }
        if (a.measures().isEmpty() && a.groupBy().isEmpty()) {
            d.add(E_INVALID_ARGUMENT, "measures", "An aggregate needs at least one groupBy column or measure.", "Add what to group by or what to compute.");
            return null;
        }
        for (int i = 0; i < a.measures().size(); i++) {
            var m = a.measures().get(i);
            String f = "measures[" + i + "]";
            if (m.where() != null) x.predicate(m.where(), f + ".where");
            ColumnType measure = measure(m, t, x, f);
            if (measure == null) continue;
            if (!seen.add(TableType.key(m.as()))) {
                d.add(E_DUPLICATE_OUTPUT_COLUMN, f + ".as", "Two output columns are named '" + m.as() + "' (names ignore case).",
                        "Give every groupBy column and measure a distinct name.", "received", m.as());
                continue;
            }
            out.add(measure);
        }
        return TableType.of(out);
    }

    private @Nullable ColumnType measure(Step.AggregateMeasure m, TableType t, ExprTyper x, String f) {
        boolean star = "*".equals(m.of());
        boolean conditional = m.where() != null;
        switch (m.fn()) {
            case count -> {
                if (!star && x.column(m.of(), f + ".of") == null) return null;
                return ColumnType.computed(m.as(), ScalarType.NUMBER, false);
            }
            case countIf -> {
                if (!conditional) {
                    d.add(E_COUNTIF_NO_PREDICATE, f + ".where", "countIf needs a where predicate.",
                            "Add the condition to count, e.g. status = returned; or use count for all rows.");
                    return null;
                }
                if (!star && x.column(m.of(), f + ".of") == null) return null;
                return ColumnType.computed(m.as(), ScalarType.NUMBER, false);
            }
            case countDistinct -> {
                if (star) {
                    d.add(E_INVALID_ARGUMENT, f + ".of", "countDistinct needs a column, not '*'.", "Name the column whose distinct values to count.");
                    return null;
                }
                if (x.column(m.of(), f + ".of") == null) return null;
                return ColumnType.computed(m.as(), ScalarType.NUMBER, false);
            }
            case sum, avg, min, max -> {
                if (star) {
                    d.add(E_INVALID_ARGUMENT, f + ".of", m.fn() + " needs a column, not '*'.", "Name the numeric column to " + m.fn() + ".");
                    return null;
                }
                ColumnType c = x.column(m.of(), f + ".of");
                if (c == null) return null;
                boolean minMax = m.fn() == Step.AggFn.min || m.fn() == Step.AggFn.max;
                boolean ok = ScalarType.isNumeric(c.type()) || (minMax && ScalarType.isTemporal(c.type()));
                if (!ok) {
                    var numeric = t.columns().stream().filter(k -> ScalarType.isNumeric(k.type())).map(ColumnType::name).toList();
                    d.add(E_TYPE_MISMATCH, f + ".of",
                            m.fn() + " expects " + (minMax ? "numeric or date" : "numeric") + ", got " + c.type().render() + "(" + c.name() + ")",
                            "Available numeric columns: " + Diags.list(numeric) + ".",
                            "expected", minMax ? "number | currency | percent | date | datetime" : "number | currency | percent",
                            "actual", c.type().render(), "candidates", numeric);
                    return null;
                }
                if (c.mayContainErrors()) {
                    d.add(W_MAY_CONTAIN_ERRORS, f + ".of",
                            "'" + c.name() + "' has cells showing Excel errors; in Excel, " + m.fn() + " over an error is an error.",
                            "The preview will ask the user whether to exclude those cells.", "column", c.name());
                }
                if (c.numbersAsText() && (m.fn() == Step.AggFn.sum || m.fn() == Step.AggFn.avg)) {
                    d.add(W_NUMBERS_STORED_AS_TEXT, f + ".of",
                            "'" + c.name() + "' has numbers stored as text; Excel's " + m.fn().name().toUpperCase() + " silently skips them.",
                            "The preview shows how many; Sheaf counts them as numbers.", "column", c.name());
                }
                return ColumnType.computed(m.as(), c.type(), c.nullable() || conditional);
            }
        }
        return null;
    }

    private @Nullable TableType sort(Step.SortStep s, TableType t, ExprTyper x) {
        if (s.by().isEmpty()) {
            d.add(E_INVALID_ARGUMENT, "by", "sort needs at least one key.", "List the columns to sort by.");
            return null;
        }
        for (int i = 0; i < s.by().size(); i++) {
            ColumnType c = x.column(s.by().get(i).col(), "by[" + i + "].col");
            if (c != null && c.type() instanceof CategoricalType cat && cat.ordering() == ScalarType.Ordering.missing) {
                d.add(E_SORT_CATEGORICAL_UNORDERED, "by[" + i + "].col",
                        "Cannot sort by '" + c.name() + "': its values have an order, but the order isn't declared.",
                        "Declare the order of its values in the semantic model (e.g. low, medium, high), or sort by another column.",
                        "col", c.name());
            }
        }
        return t;
    }

    private @Nullable TableType limit(Step.LimitStep l, TableType t, @Nullable Step previous) {
        if (l.n() < 1) {
            d.add(E_LIMIT_NOT_POSITIVE, "n", "limit must be at least 1; got " + l.n() + ".", "For \"top 5\", use n = 5.", "received", l.n());
            return null;
        }
        if (!(previous instanceof Step.SortStep)) {
            d.add(W_LIMIT_WITHOUT_SORT, null,
                    "limit step is not immediately preceded by a sort step. Row order is undefined; results may be non-deterministic.",
                    "Add a sort step before limit to produce deterministic top-N output.");
        }
        return t;
    }

    // ── join / lookup ──────────────────────────────────────────────────────────

    /** The key column on each side, resolved and checked against the approved edges; null after a diagnostic. */
    private record Keys(TypeEnvironment.EntitySchema with, ColumnType left, TypeEnvironment.ColumnSchema right) {}

    private @Nullable Keys keys(String withName, Step.JoinKey on, ExprTyper x) {
        var with = env.entity(withName);
        if (with.isEmpty()) {
            var names = env.entities().keySet();
            d.add(E_JOIN_ENTITY_UNKNOWN, "with", "'" + withName + "' is not a known table." + Diags.didYouMean(withName, names),
                    "Known tables: " + Diags.list(names.stream().sorted().toList()) + ".", "received", withName,
                    "candidates", Diags.candidates(withName, names));
            return null;
        }
        ColumnType left = x.column(on.left(), "on.left");
        var rightCol = with.get().column(on.right());
        if (rightCol.isEmpty()) {
            var names = with.get().columns().stream().map(TypeEnvironment.ColumnSchema::name).toList();
            d.add(E_UNKNOWN_COLUMN, "on.right", "'" + with.get().name() + "' has no column '" + on.right() + "'." + Diags.didYouMean(on.right(), names),
                    "Columns of " + with.get().name() + ": " + Diags.list(names) + ".", "received", on.right(),
                    "candidates", Diags.candidates(on.right(), names));
        }
        if (left == null || rightCol.isEmpty()) return null;

        String withEntity = with.get().name();
        String rightName = rightCol.get().name();
        var origin = left.origin();
        TypeEnvironment.JoinEdge edge = origin == null ? null : env.joins().stream()
                .filter(e -> (same(e.fromEntity(), origin.entity()) && same(e.fromColumn(), origin.column())
                                && same(e.toEntity(), withEntity) && same(e.toColumn(), rightName))
                        || (same(e.toEntity(), origin.entity()) && same(e.toColumn(), origin.column())
                                && same(e.fromEntity(), withEntity) && same(e.fromColumn(), rightName)))
                .findFirst().orElse(null);
        if (edge == null || !edge.approved()) {
            var approved = env.joins().stream().filter(TypeEnvironment.JoinEdge::approved)
                    .map(e -> e.fromEntity() + "." + e.fromColumn() + " → " + e.toEntity() + "." + e.toColumn()).toList();
            d.add(E_UNAPPROVED_JOIN, "on",
                    "Linking " + (origin == null ? left.name() : origin.entity() + "." + origin.column()) + " to " + withEntity + "." + rightName
                            + (edge == null ? " is not a declared link." : " is proposed but not approved."),
                    approved.isEmpty() ? "No links are approved yet. Ask the user to approve the link in the catalog."
                            : "Approved links: " + Diags.list(approved) + ".",
                    "left", left.name(), "right", withEntity + "." + rightName, "candidates", approved);
            return null;
        }
        Typed lt = Typed.column(left);
        Typed rt = Typed.of(rightCol.get().type(), rightCol.get().nullable());
        if (!ExprTyper.compatible(left.type(), rt) && !ExprTyper.compatible(rightCol.get().type(), lt)) {
            d.add(E_JOIN_KEY_TYPE_MISMATCH, "on", "Keys don't match: " + left.type().render() + " vs " + rightCol.get().type().render() + ".",
                    "Link columns holding the same kind of value.", "expected", left.type().render(), "actual", rightCol.get().type().render());
            return null;
        }
        return new Keys(with.get(), left, rightCol.get());
    }

    private static boolean same(String a, String b) {
        return a.equalsIgnoreCase(b);
    }

    /** "name", or "name (Regions)" when taken, then "name (Regions) 2"… Never ambiguous, whatever the header contains. */
    static String uniqueName(String wanted, String entity, Set<String> takenKeys) {
        if (takenKeys.add(TableType.key(wanted))) return wanted;
        String base = wanted + " (" + entity + ")";
        String name = base;
        for (int i = 2; !takenKeys.add(TableType.key(name)); i++) name = base + " " + i;
        return name;
    }

    private @Nullable TableType join(Step.JoinStep j, TableType t, ExprTyper x, String sourceName) {
        Keys k = keys(j.with(), j.on(), x);
        if (k == null) return null;
        TableType right = k.with().tableType();
        // Union of both schemas; a right-hand name that collides (ignoring case) gets its table in brackets.
        Set<String> taken = new HashSet<>();
        List<ColumnType> out = new ArrayList<>();
        for (ColumnType c : t.columns()) {
            taken.add(TableType.key(c.name()));
            out.add(c);
        }
        for (ColumnType c : right.columns()) {
            ColumnType r = c.withName(uniqueName(c.name(), k.with().name(), taken));
            out.add(j.kind() == Step.JoinKind.left ? r.withNullable(true) : r);
        }
        return TableType.of(out);
    }

    private @Nullable TableType lookup(Step.LookupStep l, TableType t, ExprTyper x, String sourceName) {
        Keys k = keys(l.with(), l.on(), x);
        if (k == null) return null;
        if (!k.right().unique()) {
            d.add(E_LOOKUP_KEY_NOT_UNIQUE, "on.right",
                    "'" + k.right().name() + "' isn't unique in " + k.with().name() + ", so a lookup could match several rows.",
                    "Look up on a column with one row per value (a key), or use join if multiplying rows is intended.",
                    "column", k.with().name() + "." + k.right().name(),
                    "candidates", k.with().columns().stream().filter(TypeEnvironment.ColumnSchema::unique).map(TypeEnvironment.ColumnSchema::name).toList());
            return null;
        }
        TableType right = k.with().tableType();
        List<ColumnType> take = new ArrayList<>();
        if (l.take() == null) {
            for (ColumnType c : right.columns()) if (!same(c.name(), k.right().name())) take.add(c);
        } else {
            for (int i = 0; i < l.take().size(); i++) {
                String name = l.take().get(i);
                var c = right.find(name);
                if (c.isEmpty()) {
                    d.add(E_UNKNOWN_COLUMN, "take[" + i + "]", "'" + k.with().name() + "' has no column '" + name + "'." + Diags.didYouMean(name, right.names()),
                            "Columns of " + k.with().name() + ": " + Diags.list(right.names()) + ".", "received", name,
                            "candidates", Diags.candidates(name, right.names()));
                    return null;
                }
                take.add(c.get());
            }
        }
        Set<String> taken = new HashSet<>();
        List<ColumnType> out = new ArrayList<>();
        for (ColumnType c : t.columns()) {
            taken.add(TableType.key(c.name()));
            out.add(c);
        }
        // Unmatched rows get nulls, like XLOOKUP's not-found; the looked-up columns are always nullable.
        for (ColumnType c : take) out.add(c.withName(uniqueName(c.name(), k.with().name(), taken)).withNullable(true));
        return TableType.of(out);
    }

    // ── pivot / periodCompare / project ────────────────────────────────────────

    private @Nullable TableType pivot(Step.PivotStep p, TableType t, ExprTyper x) {
        List<ColumnType> rows = new ArrayList<>();
        for (int i = 0; i < p.rows().size(); i++) {
            ColumnType c = x.column(p.rows().get(i), "rows[" + i + "]");
            if (c != null) rows.add(c);
        }
        ColumnType cols = x.column(p.cols(), "cols");
        ColumnType values = x.column(p.values(), "values");
        if (cols == null || values == null || rows.size() != p.rows().size()) return null;

        boolean bounded = cols.type() instanceof ScalarType.NumberType && cols.cardinality() != null;
        if (!(ScalarType.isText(cols.type()) || bounded)) {
            d.add(E_PIVOT_NON_CATEGORICAL_COLS, "cols",
                    "Cannot pivot on '" + cols.name() + "' (" + cols.type().render() + "); its values would become an unbounded set of columns.",
                    "Pivot on a categorical or text column, or on a date part such as quarterOf or monthOf.",
                    "expected", "categorical | string | date part", "actual", cols.type().render());
            return null;
        }
        if (cols.cardinality() != null && cols.cardinality() > PIVOT_CARDINALITY_LIMIT) {
            d.add(E_PIVOT_HIGH_CARDINALITY, "cols",
                    "Pivoting on '" + cols.name() + "' would create " + cols.cardinality() + " columns; the limit is " + PIVOT_CARDINALITY_LIMIT + ".",
                    "Group by '" + cols.name() + "' in rows instead, or filter it to fewer values first.",
                    "cardinality", cols.cardinality(), "limit", PIVOT_CARDINALITY_LIMIT);
            return null;
        }
        if (!ScalarType.isNumeric(values.type())) {
            d.add(E_PIVOT_VALUE_NOT_NUMERIC, "values", "Pivot values must be numeric; '" + values.name() + "' is " + values.type().render() + ".",
                    "Pivot a measure such as a sum or count.", "expected", "number | currency | percent", "actual", values.type().render());
            return null;
        }
        return new TableType(rows, ColumnType.computed("*", values.type(), true));
    }

    private @Nullable TableType periodCompare(Step.PeriodCompareStep p, TableType t, ExprTyper x, boolean onlyFiltersSoFar, String sourceName) {
        if (!onlyFiltersSoFar) {
            d.add(E_PERIOD_COMPARE_PLACEMENT, null, "periodCompare must come first, after filters only.",
                    "Move periodCompare before any derive, aggregate, sort, limit, join or lookup; it does its own grouping.");
            return null;
        }
        var metric = env.metrics().values().stream().filter(m -> same(m.name(), p.metric()) && same(m.entity(), sourceName)).findFirst();
        if (metric.isEmpty()) {
            var names = env.metrics().values().stream().filter(m -> same(m.entity(), sourceName)).map(TypeEnvironment.Metric::name).sorted().toList();
            d.add(E_UNKNOWN_METRIC, "metric", "'" + p.metric() + "' is not a metric of " + sourceName + "." + Diags.didYouMean(p.metric(), names),
                    names.isEmpty() ? "No metrics are defined for " + sourceName + " yet." : "Metrics of " + sourceName + ": " + Diags.list(names) + ".",
                    "received", p.metric(), "candidates", Diags.candidates(p.metric(), names));
            return null;
        }
        var dim = env.timeDimensions().values().stream().filter(m -> same(m.name(), p.timeDim()) && same(m.entity(), sourceName)).findFirst();
        if (dim.isEmpty()) {
            var names = env.timeDimensions().values().stream().filter(m -> same(m.entity(), sourceName)).map(TypeEnvironment.TimeDimension::name).sorted().toList();
            d.add(E_PERIOD_COMPARE_NO_TIME_DIM, "timeDim", "'" + p.timeDim() + "' is not a time dimension of " + sourceName + ".",
                    names.isEmpty() ? "No time dimensions are defined for " + sourceName + " yet." : "Time dimensions: " + Diags.list(names) + ".",
                    "received", p.timeDim(), "candidates", Diags.candidates(p.timeDim(), names));
            return null;
        }
        var td = dim.get();
        if (!td.grains().contains(p.grain()) || (p.grain() == Step.Grain.week && td.weekStart() == null)) {
            d.add(E_PERIOD_COMPARE_UNSUPPORTED_GRAIN, "grain",
                    p.grain() == Step.Grain.week && td.grains().contains(p.grain())
                            ? "Weekly comparison needs a declared week start for " + td.name() + "."
                            : td.name() + " does not support the '" + p.grain() + "' grain.",
                    "Supported grains: " + Diags.list(td.grains().stream().map(Enum::name).sorted().toList()) + ".", "received", p.grain().name());
            return null;
        }
        Pattern period = PERIOD.get(p.grain());
        for (var entry : List.of(Map.entry("current", p.current()), Map.entry("prior", p.prior()))) {
            if (!period.matcher(entry.getValue()).matches()) {
                d.add(E_PERIOD_COMPARE_INVALID_PERIOD, entry.getKey(),
                        "'" + entry.getValue() + "' is not a valid " + p.grain() + " period.",
                        "Write " + p.grain() + " periods like " + example(p.grain()) + ".", "received", entry.getValue());
                return null;
            }
        }
        List<ColumnType> out = new ArrayList<>();
        var groupBy = p.groupBy() == null ? List.<String>of() : p.groupBy();
        for (int i = 0; i < groupBy.size(); i++) {
            ColumnType c = x.column(groupBy.get(i), "groupBy[" + i + "]");
            if (c == null) return null;
            out.add(c);
        }
        String m = metric.get().name().toLowerCase().replaceAll("[^a-z0-9]+", "_");
        out.add(ColumnType.computed("current_" + m, metric.get().type(), true));
        out.add(ColumnType.computed("prior_" + m, metric.get().type(), true));
        out.add(ColumnType.computed("delta", metric.get().type(), true));
        out.add(ColumnType.computed("delta_pct", ScalarType.PERCENT, true));
        return TableType.of(out);
    }

    private static String example(Step.Grain g) {
        return switch (g) {
            case year -> "2025";
            case quarter -> "2025-Q1";
            case month -> "2025-03";
            case week -> "2025-W04";
            case day -> "2025-03-31";
        };
    }

    private @Nullable TableType project(Step.ProjectStep p, ExprTyper x, String base) {
        if (p.columns().isEmpty()) {
            d.add(E_INVALID_ARGUMENT, "columns", "project needs at least one column.", "List the output columns in order.");
            return null;
        }
        List<ColumnType> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        formatted.clear();
        for (int i = 0; i < p.columns().size(); i++) {
            var c = p.columns().get(i);
            String f = "columns[" + i + "]";
            Typed e = x.expr(c.expr(), f + ".expr");
            if (e == null) continue;
            if (!seen.add(TableType.key(c.as()))) {
                d.add(E_DUPLICATE_OUTPUT_COLUMN, f + ".as", "Two output columns are named '" + c.as() + "' (names ignore case).",
                        "Give every projected column a distinct name.", "received", c.as());
                continue;
            }
            if (e.isNullLiteral()) unmapped.add(TableType.key(c.as()));
            else unmapped.remove(TableType.key(c.as()));
            if (e.formatted()) formatted.put(TableType.key(c.as()), base + "/columns/" + i + "/expr");
            out.add(ColumnType.computed(c.as(), typeOrString(e), e.nullable(), e.cardinality(), e.origin()));
        }
        return TableType.of(out);
    }

    /** A null literal has no type of its own; as a column it is a nullable string. */
    private static ScalarType typeOrString(Typed e) {
        return e.type() == null ? ScalarType.STRING : e.type();
    }

    // ── Sinks ──────────────────────────────────────────────────────────────────

    private void refuseFormattedOutputs(String sinkName) {
        for (String pointer : formatted.values()) {
            d.addAt(E_TOTEXT_OUTSIDE_CONCAT, pointer,
                    "toText into a " + sinkName + " would store numbers or dates as text, which breaks sums and sorting downstream.",
                    "Keep the typed value and let the number format show it; use toText only inside a concat.");
        }
    }

    private void sink(Sink sink, TableType t, boolean noSteps, String source) {
        switch (sink) {
            case Sink.NewSheetSink s -> {
                if (s.name() == null || s.name().isBlank()) {
                    d.add(E_SINK_NEW_SHEET_NAME_MISSING, "name", "A new-sheet sink needs a sheet name.", "Name the sheet to create, e.g. \"Analysis\".");
                } else if (!validSheetName(s.name())) {
                    d.add(E_SINK_SHEET_NAME_INVALID, "name",
                            "'" + s.name() + "' isn't a valid Excel sheet name.",
                            "Use at most 31 characters, none of [ ] : * ? / \\, not starting or ending with an apostrophe, and not 'History'.",
                            "received", s.name());
                } else if (env.sheetNames().stream().anyMatch(n -> n.equalsIgnoreCase(s.name()))) {
                    d.add(E_SINK_SHEET_NAME_TAKEN, "name", "A sheet named '" + s.name() + "' already exists (names ignore case).",
                            "Pick a name no sheet uses.", "received", s.name());
                }
                if (s.anchor() == null || !CELL.matcher(s.anchor()).matches()) {
                    d.add(E_SINK_ANCHOR_INVALID, "anchor", "'" + s.anchor() + "' is not a cell reference.", "Use a single cell such as \"A1\".", "received", s.anchor());
                }
                if (noSteps) {
                    d.add(W_TRIVIAL_PLAN, null, "This plan has no transformation steps and writes the entire " + source + " table to the sink.",
                            "Confirm this is intentional. If the question implies filtering or transformation, the plan may be incomplete.");
                }
                refuseFormattedOutputs("new sheet");
            }
            case Sink.AnchorSink s -> {
                if (noSteps) {
                    d.add(E_EMPTY_PIPELINE_WRITE, null, "A plan with no steps would overwrite an existing range with a whole table.",
                            "Write to a new sheet instead, or add the steps the question asks for.");
                }
                refuseFormattedOutputs("sheet");
            }
            case Sink.TemplateSink s -> template(s, t);
        }
    }

    static boolean validSheetName(String name) {
        return name.length() <= SHEET_NAME_MAX && !name.isBlank()
                && !SHEET_NAME_FORBIDDEN.matcher(name).find()
                && !name.startsWith("'") && !name.endsWith("'")
                && !name.equalsIgnoreCase("History");
    }

    private void template(Sink.TemplateSink s, TableType t) {
        var schema = env.templates().get(s.templateId());
        if (schema == null) {
            var ids = env.templates().keySet();
            d.add(E_TEMPLATE_UNKNOWN, "templateId", "'" + s.templateId() + "' is not an imported template." + Diags.didYouMean(s.templateId(), ids),
                    "Imported templates: " + Diags.list(ids.stream().sorted().toList()) + ".", "received", s.templateId(),
                    "candidates", Diags.candidates(s.templateId(), ids));
            return;
        }
        if (s.headerRow() < 1 || s.firstDataRow() <= s.headerRow()) {
            d.add(E_INVALID_ARGUMENT, "firstDataRow", "firstDataRow must be below headerRow.",
                    "The template header is on row " + schema.headerRow() + "; values start on row " + schema.firstDataRow() + ".");
            return;
        }
        if (t.dynamic()) {
            d.add(E_TEMPLATE_DYNAMIC_COLUMNS, null, "A pivot's columns depend on the data, so they can't be matched to a template.",
                    "Replace the pivot with an aggregate and a project that names each template column.");
            return;
        }
        var writable = schema.columns().stream().filter(c -> !c.formula()).toList();
        var formulaKeys = schema.columns().stream().filter(TypeEnvironment.TemplateColumn::formula).map(c -> TableType.key(c.name())).toList();
        var wanted = writable.stream().map(TypeEnvironment.TemplateColumn::name).toList();
        var wantedKeys = wanted.stream().map(TableType::key).toList();
        var haveKeys = t.names().stream().map(TableType::key).toList();

        boolean shapeOk = true;
        for (String w : wanted) {
            if (!haveKeys.contains(TableType.key(w))) {
                shapeOk = false;
                d.add(E_TEMPLATE_COLUMN_MISSING, "columns", "The template's '" + w + "' column has no value in the plan." + Diags.didYouMean(w, t.names()),
                        "Add a project column named '" + w + "'. To leave it empty on purpose, map it to a null literal.",
                        "column", w, "candidates", Diags.candidates(w, t.names()));
            }
        }
        for (String h : t.names()) {
            if (!wantedKeys.contains(TableType.key(h))) {
                shapeOk = false;
                d.add(E_TEMPLATE_EXTRA_COLUMN, "columns",
                        formulaKeys.contains(TableType.key(h)) ? "'" + h + "' is a formula column in the template; it fills itself and must not be written."
                                : "The plan outputs '" + h + "', which is not a template column." + Diags.didYouMean(h, wanted),
                        "Template columns to fill, in order: " + Diags.list(wanted) + ".", "column", h, "candidates", Diags.candidates(h, wanted));
            }
        }
        if (shapeOk && !haveKeys.equals(wantedKeys)) {
            d.add(E_TEMPLATE_COLUMN_ORDER, "columns", "The plan's columns are in a different order from the template's.",
                    "Order the project columns as: " + Diags.list(wanted) + ".", "expected", wanted, "actual", t.names());
        }

        for (var tc : writable) {
            var out = t.find(tc.name());
            if (out.isEmpty()) continue;
            String key = TableType.key(tc.name());
            if (unmapped.contains(key)) {
                d.add(W_UNMAPPED_TEMPLATE_COLUMN, "columns", "The template's '" + tc.name() + "' column will be left empty.",
                        "Map it to a source column if one fits.", "column", tc.name());
                continue;
            }
            if (formatted.containsKey(key) && !(tc.expected() instanceof ScalarType.StringType)) {
                d.addAt(E_TOTEXT_OUTSIDE_CONCAT, formatted.get(key),
                        "'" + tc.name() + "' isn't text-formatted in the template, so toText would store a number or date as text.",
                        "Write the typed value; the template's number format will display it.", "column", tc.name());
                continue;
            }
            ScalarType got = out.get().type();
            if (tc.expected() != null && !formatted.containsKey(key) && !templateAccepts(tc.expected(), got)) {
                d.add(E_TEMPLATE_TYPE_INCOMPATIBLE, "columns",
                        "The template's '" + tc.name() + "' column expects " + tc.expected().render() + " but the plan gives " + got.render() + ".",
                        "Map a column of the right type.",
                        "column", tc.name(), "expected", tc.expected().render(), "actual", got.render());
                continue;
            }
            if (tc.validationList() != null && got instanceof CategoricalType cat && cat.members() != null) {
                var outside = new LinkedHashSet<String>();
                for (String m : cat.members()) {
                    if (tc.validationList().stream().noneMatch(v -> v.equalsIgnoreCase(m))) outside.add(m);
                }
                if (!outside.isEmpty()) {
                    d.add(E_TEMPLATE_VALUE_NOT_IN_LIST, "columns",
                            "The template only allows " + Diags.list(tc.validationList()) + " in '" + tc.name() + "', but the plan can write "
                                    + Diags.list(List.copyOf(outside)) + ".",
                            "Map the values with a case expression, e.g. 'active' → one of " + Diags.list(tc.validationList()) + ".",
                            "column", tc.name(), "expected", tc.validationList(), "actual", List.copyOf(outside));
                }
            }
        }
    }

    private static boolean templateAccepts(ScalarType expected, ScalarType got) {
        return switch (expected) {
            case ScalarType.StringType s -> true;
            case CategoricalType c -> ScalarType.isText(got);
            case ScalarType.NumberType n -> ScalarType.isNumeric(got);
            case CurrencyType c -> ScalarType.isNumeric(got)
                    && !(got instanceof CurrencyType g && ExprTyper.unitsDiffer(c, g));
            case ScalarType.PercentType p -> got instanceof ScalarType.PercentType || got instanceof ScalarType.NumberType;
            case ScalarType.DateType dt -> ScalarType.isTemporal(got);
            case ScalarType.DateTimeType dt -> ScalarType.isTemporal(got);
            case ScalarType.BooleanType b -> got instanceof ScalarType.BooleanType;
        };
    }
}
