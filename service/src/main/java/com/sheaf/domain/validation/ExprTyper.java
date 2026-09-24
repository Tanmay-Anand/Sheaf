package com.sheaf.domain.validation;

import com.sheaf.domain.common.Nullable;
import com.sheaf.domain.ir.Expr;
import com.sheaf.domain.ir.Predicate;
import com.sheaf.domain.ir.Step;
import com.sheaf.domain.ir.ValueType;
import com.sheaf.domain.types.ColumnType;
import com.sheaf.domain.types.ScalarType;
import com.sheaf.domain.types.ScalarType.CategoricalType;
import com.sheaf.domain.types.ScalarType.CurrencyType;
import com.sheaf.domain.types.ScalarType.NumberType;
import com.sheaf.domain.types.ScalarType.PercentType;
import com.sheaf.domain.types.TableType;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.sheaf.domain.validation.DiagnosticCode.*;

/**
 * Types expressions and predicates against one table type (ir-spec §3, §4). Every switch is over
 * a sealed type with no default branch: a new Expr or Predicate variant doesn't compile until it
 * is handled here.
 */
final class ExprTyper {

    /** Where a typed value came from; literals and parameters adapt to the column they meet. */
    enum Source { VALUE, LITERAL, PARAM }

    /**
     * The type of an expression.
     *
     * @param type      Null only for the null literal, which is compatible with anything.
     * @param source    A column/computed value, a literal, or a parameter.
     * @param value     The literal's value.
     * @param formatted Produced by a {@code toText} outside any {@code concat}: text that looks like a
     *                  number or date. Only a text-formatted template column may receive it.
     */
    record Typed(@Nullable ScalarType type, boolean nullable, @Nullable Integer cardinality,
                 Source source, @Nullable Object value, @Nullable ColumnType.Origin origin, boolean formatted) {

        static Typed of(ScalarType type, boolean nullable) {
            return new Typed(type, nullable, null, Source.VALUE, null, null, false);
        }

        static Typed bounded(ScalarType type, boolean nullable, int cardinality) {
            return new Typed(type, nullable, cardinality, Source.VALUE, null, null, false);
        }

        static Typed column(ColumnType c) {
            return new Typed(c.type(), c.nullable(), c.cardinality(), Source.VALUE, null, c.origin(), false);
        }

        boolean literal() {
            return source == Source.LITERAL;
        }

        /** Literals and parameters take on the type of what they are compared with or added to. */
        boolean adaptable() {
            return source != Source.VALUE;
        }

        boolean isNullLiteral() {
            return source == Source.LITERAL && value == null;
        }

        String render() {
            return type == null ? "null" : type.render();
        }
    }

    /** Date-part expressions return small integers with a static bound; pivot relies on it. */
    private static final Map<Class<?>, Integer> DATE_PART_BOUND = Map.of(
            Expr.QuarterOfExpr.class, 4,
            Expr.MonthOfExpr.class, 12,
            Expr.IsoWeekOfExpr.class, 53,
            Expr.DayOfExpr.class, 31,
            Expr.IsoDayOfWeekExpr.class, 7);

    static final Set<String> DATE_PATTERNS = Set.of("yyyy-mm-dd", "dd/mm/yyyy", "mm/dd/yyyy", "yyyy-mm", "mmm yyyy", "yyyy");
    static final Set<String> NUMBER_PATTERNS = Set.of("0", "0.00", "#,##0", "#,##0.00", "0%", "0.0%");

    private final TableType table;
    private final Set<String> eliminated;
    private final @Nullable Step.AggregateStep previousAggregate;
    private final Map<String, ValueType> params;
    private final Diags d;
    private int concatDepth;

    /**
     * @param eliminated        Columns that existed earlier in the pipeline but no longer do.
     * @param previousAggregate The step immediately before, when it is an aggregate (sumAll needs it).
     * @param params            Declared parameters, by lower-cased name.
     */
    ExprTyper(TableType table, Set<String> eliminated, @Nullable Step.AggregateStep previousAggregate,
              Map<String, ValueType> params, Diags d) {
        this.table = table;
        this.eliminated = eliminated;
        this.previousAggregate = previousAggregate;
        this.params = params;
        this.d = d;
    }

    // ── Columns ────────────────────────────────────────────────────────────────

    @Nullable ColumnType column(String name, String field) {
        var c = table.find(name);
        if (c.isPresent()) return c.get();
        if (eliminated.contains(TableType.key(name))) {
            d.add(E_COLUMN_NOT_IN_OUTPUT, field,
                    "Column '" + name + "' no longer exists at this step; an earlier step removed it.",
                    "Available columns at this step: " + Diags.list(table.names())
                            + ". To keep '" + name + "', add it to the earlier aggregate's groupBy or project it through.",
                    "received", name, "candidates", Diags.candidates(name, table.names()));
        } else {
            d.add(E_UNKNOWN_COLUMN, field,
                    "Column '" + name + "' does not exist." + Diags.didYouMean(name, table.names()),
                    "Available columns: " + Diags.list(table.names()) + ".",
                    "received", name, "suggestion", Diags.closest(name, table.names()),
                    "candidates", Diags.candidates(name, table.names()));
        }
        return null;
    }

    // ── Expressions ────────────────────────────────────────────────────────────

    @Nullable Typed expr(Expr e, String field) {
        return switch (e) {
            case Expr.ColRef c -> {
                ColumnType col = column(c.col(), field + ".col");
                yield col == null ? null : Typed.column(col);
            }
            case Expr.Lit l -> literal(l.value(), l.valueType(), field);
            case Expr.ParamRef p -> param(p.name(), field);
            case Expr.AddExpr a -> additive("add", a.a(), a.b(), field);
            case Expr.SubExpr s -> additive("sub", s.a(), s.b(), field);
            case Expr.MulExpr m -> multiply(m.a(), m.b(), field);
            case Expr.DivExpr v -> divide(v.a(), v.b(), field);
            case Expr.RatioExpr r -> ratio("ratio", r.numerator(), r.denominator(), ScalarType.NUMBER, field);
            case Expr.PctExpr p -> ratio("pct", p.numerator(), p.denominator(), ScalarType.PERCENT, field);
            case Expr.YearOfExpr y -> datePart(y, y.col(), "yearOf", field);
            case Expr.QuarterOfExpr q -> datePart(q, q.col(), "quarterOf", field);
            case Expr.MonthOfExpr m -> datePart(m, m.col(), "monthOf", field);
            case Expr.IsoWeekOfExpr w -> datePart(w, w.col(), "isoWeekOf", field);
            case Expr.DayOfExpr dd -> datePart(dd, dd.col(), "dayOf", field);
            case Expr.IsoDayOfWeekExpr dw -> datePart(dw, dw.col(), "isoDayOfWeek", field);
            case Expr.BucketExpr b -> bucket(b, field);
            case Expr.CoalesceExpr c -> coalesce(c, field);
            case Expr.CaseExpr c -> caseExpr(c, field);
            case Expr.SumAllExpr s -> sumAll(s, field);
            case Expr.ConcatExpr c -> concat(c, field);
            case Expr.TrimExpr t -> text("trim", t.arg(), field);
            case Expr.UpperExpr u -> text("upper", u.arg(), field);
            case Expr.LowerExpr l -> text("lower", l.arg(), field);
            case Expr.SplitPartExpr s -> splitPart(s, field);
            case Expr.ToTextExpr t -> toText(t, field);
        };
    }

    /**
     * Types a literal. Without {@code valueType} a string is text, a number is a number, a boolean is
     * a boolean. With it, the value must match: a date literal is an ISO date string.
     */
    @Nullable Typed literal(@Nullable Object v, @Nullable ValueType valueType, String field) {
        if (v == null) return new Typed(null, true, null, Source.LITERAL, null, null, false);
        ScalarType type = valueType == null ? inferred(v) : declared(v, valueType);
        if (type == null) {
            d.add(E_INVALID_ARGUMENT, field + ".value",
                    "The literal " + v + " is not a valid " + valueType.wire() + ".",
                    valueType == ValueType.DATE || valueType == ValueType.DATETIME
                            ? "Write dates as \"yyyy-mm-dd\" (and times as \"yyyy-mm-ddThh:mm:ss\")."
                            : "Give a value of the declared type, or change valueType.",
                    "received", String.valueOf(v), "expected", valueType.wire());
            return null;
        }
        Object value = v instanceof String || v instanceof Number || v instanceof Boolean ? v : String.valueOf(v);
        return new Typed(type, false, 1, Source.LITERAL, value, null, false);
    }

    private static ScalarType inferred(Object v) {
        if (v instanceof Number) return ScalarType.NUMBER;
        if (v instanceof Boolean) return ScalarType.BOOLEAN;
        return ScalarType.STRING;
    }

    private static @Nullable ScalarType declared(Object v, ValueType t) {
        return switch (t) {
            case STRING -> v instanceof String ? ScalarType.STRING : null;
            case NUMBER -> v instanceof Number ? ScalarType.NUMBER : null;
            case BOOLEAN -> v instanceof Boolean ? ScalarType.BOOLEAN : null;
            case DATE -> v instanceof String s && isIsoDate(s) ? ScalarType.DATE : null;
            case DATETIME -> v instanceof String s && isIsoDateTime(s) ? ScalarType.DATETIME : null;
        };
    }

    static ScalarType typeOf(ValueType t) {
        return switch (t) {
            case STRING -> ScalarType.STRING;
            case NUMBER -> ScalarType.NUMBER;
            case BOOLEAN -> ScalarType.BOOLEAN;
            case DATE -> ScalarType.DATE;
            case DATETIME -> ScalarType.DATETIME;
        };
    }

    @Nullable Typed param(String name, String field) {
        ValueType t = params.get(TableType.key(name));
        if (t == null) {
            d.add(E_UNKNOWN_PARAM, field + ".name", "The plan uses parameter '" + name + "' but doesn't declare it.",
                    params.isEmpty() ? "Declare it in the plan's params with a valueType, e.g. { \"name\": \"asOf\", \"valueType\": \"date\" }."
                            : "Declared parameters: " + Diags.list(params.keySet()) + ".",
                    "received", name, "candidates", Diags.candidates(name, params.keySet()));
            return null;
        }
        return new Typed(typeOf(t), false, null, Source.PARAM, null, null, false);
    }

    private @Nullable Typed additive(String op, Expr ea, Expr eb, String field) {
        Typed a = expr(ea, field + ".a");
        Typed b = expr(eb, field + ".b");
        if (a == null || b == null) return null;
        boolean nullable = a.nullable() || b.nullable();
        // A bare number literal or parameter is unit-less: it takes the other operand's numeric type.
        if (a.adaptable() && a.type() instanceof NumberType && b.type() != null && ScalarType.isNumeric(b.type())) return Typed.of(b.type(), nullable);
        if (b.adaptable() && b.type() instanceof NumberType && a.type() != null && ScalarType.isNumeric(a.type())) return Typed.of(a.type(), nullable);
        if (a.type() instanceof CurrencyType ca && b.type() instanceof CurrencyType cb) {
            if (unitsDiffer(ca, cb)) return unitMismatch(op, ca, cb, field);
            return Typed.of(ca.unit() != null ? ca : cb, nullable);
        }
        if (a.type() instanceof NumberType && b.type() instanceof NumberType) return Typed.of(ScalarType.NUMBER, nullable);
        if (a.type() instanceof PercentType && b.type() instanceof PercentType) return Typed.of(ScalarType.PERCENT, nullable);
        return incomparable(op, a, b, field);
    }

    private @Nullable Typed multiply(Expr ea, Expr eb, String field) {
        Typed a = expr(ea, field + ".a");
        Typed b = expr(eb, field + ".b");
        if (a == null || b == null) return null;
        boolean nullable = a.nullable() || b.nullable();
        ScalarType ta = a.type(), tb = b.type();
        if (ta instanceof CurrencyType && (tb instanceof NumberType || tb instanceof PercentType)) return Typed.of(ta, nullable);
        if (tb instanceof CurrencyType && (ta instanceof NumberType || ta instanceof PercentType)) return Typed.of(tb, nullable);
        if ((ta instanceof NumberType || ta instanceof PercentType) && (tb instanceof NumberType || tb instanceof PercentType)) {
            return Typed.of(ta instanceof PercentType && tb instanceof PercentType ? ScalarType.PERCENT : ScalarType.NUMBER, nullable);
        }
        return incomparable("mul", a, b, field);
    }

    private @Nullable Typed divide(Expr ea, Expr eb, String field) {
        Typed a = expr(ea, field + ".a");
        Typed b = expr(eb, field + ".b");
        if (a == null || b == null) return null;
        boolean nullable = a.nullable() || b.nullable();
        ScalarType ta = a.type(), tb = b.type();
        if (ta instanceof CurrencyType ca && tb instanceof CurrencyType cb) {
            if (unitsDiffer(ca, cb)) return unitMismatch("div", ca, cb, field);
            return Typed.of(ScalarType.NUMBER, nullable);
        }
        if (ta instanceof CurrencyType && (tb instanceof NumberType || tb instanceof PercentType)) return Typed.of(ta, nullable);
        if ((ta instanceof NumberType || ta instanceof PercentType) && (tb instanceof NumberType || tb instanceof PercentType)) {
            return Typed.of(ScalarType.NUMBER, nullable);
        }
        return incomparable("div", a, b, field);
    }

    private @Nullable Typed ratio(String op, Expr en, Expr ed, ScalarType out, String field) {
        Typed a = expr(en, field + ".numerator");
        Typed b = expr(ed, field + ".denominator");
        if (a == null || b == null) return null;
        if (a.type() == null || b.type() == null || !ScalarType.isNumeric(a.type()) || !ScalarType.isNumeric(b.type())) {
            d.add(E_TYPE_MISMATCH, field, op + " expects two numeric operands, got " + a.render() + " and " + b.render() + ".",
                    "Use numeric columns (number, currency or percent) for both sides of " + op + ".",
                    "expected", "number | currency | percent", "actual", a.render() + ", " + b.render());
            return null;
        }
        if (a.type() instanceof CurrencyType ca && b.type() instanceof CurrencyType cb && unitsDiffer(ca, cb)) {
            return unitMismatch(op, ca, cb, field);
        }
        return Typed.of(out, a.nullable() || b.nullable());
    }

    private @Nullable Typed datePart(Expr e, String col, String op, String field) {
        ColumnType c = column(col, field + ".col");
        if (c == null) return null;
        if (!ScalarType.isTemporal(c.type())) {
            d.add(E_DATE_EXTRACT_NON_DATE, field + ".col",
                    op + " needs a date column; '" + col + "' is " + c.type().render() + ".",
                    "Date columns here: " + Diags.list(namesWhere(ScalarType::isTemporal))
                            + ". A text column that only looks like dates must be corrected in the catalog first.",
                    "received", col, "expected", "date | datetime", "actual", c.type().render());
            return null;
        }
        Integer bound = DATE_PART_BOUND.get(e.getClass());
        return new Typed(ScalarType.NUMBER, c.nullable(), bound, Source.VALUE, null, null, false);
    }

    private @Nullable Typed bucket(Expr.BucketExpr b, String field) {
        ColumnType c = column(b.col(), field + ".col");
        if (c == null) return null;
        if (!ScalarType.isNumeric(c.type())) {
            d.add(E_TYPE_MISMATCH, field + ".col", "bucket needs a numeric column; '" + b.col() + "' is " + c.type().render() + ".",
                    "Bucket a number, currency or percent column.", "expected", "number | currency | percent", "actual", c.type().render());
            return null;
        }
        if (b.labels().size() != b.breaks().size() + 1) {
            d.add(E_BUCKET_BREAK_COUNT, field + ".labels",
                    "bucket has " + b.breaks().size() + " breaks but " + b.labels().size()
                            + " labels. Labels must have exactly one more entry than breaks.",
                    "With " + b.breaks().size() + " breaks you need " + (b.breaks().size() + 1)
                            + " labels: one per interval, including both open ends.",
                    "breaks", b.breaks().size(), "labels", b.labels().size(), "expected_labels", b.breaks().size() + 1);
            return null;
        }
        for (int i = 1; i < b.breaks().size(); i++) {
            if (b.breaks().get(i) <= b.breaks().get(i - 1)) {
                d.add(E_INVALID_ARGUMENT, field + ".breaks", "bucket breaks must be strictly ascending.",
                        "Sort the breaks from smallest to largest and remove duplicates.", "breaks", b.breaks());
                return null;
            }
        }
        var labels = List.copyOf(new LinkedHashSet<>(b.labels()));
        return Typed.bounded(new CategoricalType(b.col() + "_bucket", labels, ScalarType.Ordering.declared), c.nullable(), labels.size());
    }

    private @Nullable Typed coalesce(Expr.CoalesceExpr c, String field) {
        if (c.args().isEmpty()) {
            d.add(E_INVALID_ARGUMENT, field + ".args", "coalesce needs at least one argument.", "List the values to try, in order.");
            return null;
        }
        List<Typed> args = new ArrayList<>();
        for (int i = 0; i < c.args().size(); i++) {
            Typed t = expr(c.args().get(i), field + ".args[" + i + "]");
            if (t == null) return null;
            args.add(t);
        }
        ScalarType common = null;
        for (Typed t : args) {
            if (t.type() == null) continue;
            if (common == null) common = t.type();
            else if (!compatible(common, t)) {
                d.add(E_TYPE_INCOMPARABLE, field, "coalesce arguments must share a type; got " + common.render() + " and " + t.render() + ".",
                        "Give coalesce arguments of the same type, e.g. a number column with a number literal.",
                        "expected", common.render(), "actual", t.render());
                return null;
            }
        }
        boolean nullable = args.stream().allMatch(Typed::nullable);
        return common == null ? literal(null, null, field) : Typed.of(common, nullable);
    }

    private @Nullable Typed caseExpr(Expr.CaseExpr c, String field) {
        if (c.else_() == null) {
            d.add(E_CASE_NO_ELSE, field, "case expressions must have an else clause.",
                    "Add an 'else' value for rows that match no condition. Use a null literal if null is acceptable.");
            return null;
        }
        List<Typed> outs = new ArrayList<>();
        for (int i = 0; i < c.when().size(); i++) {
            var w = c.when().get(i);
            predicate(w.if_(), field + ".when[" + i + "].if");
            Typed t = valueRef(w.then(), field + ".when[" + i + "].then");
            if (t != null) outs.add(t);
        }
        Typed e = expr(c.else_(), field + ".else");
        if (e != null) outs.add(e);
        if (outs.size() < c.when().size() + 1) return null;

        // All outputs are text literals: the result is a categorical domain we know exactly.
        boolean allTextLiterals = outs.stream().allMatch(t -> t.isNullLiteral() || (t.literal() && t.type() instanceof ScalarType.StringType));
        boolean nullable = outs.stream().anyMatch(Typed::nullable);
        if (allTextLiterals) {
            var members = outs.stream().filter(t -> !t.isNullLiteral()).map(t -> (String) t.value()).distinct().toList();
            return Typed.bounded(new CategoricalType("case", members, ScalarType.Ordering.none), nullable, members.size());
        }
        ScalarType common = null;
        for (Typed t : outs) {
            if (t.type() == null) continue;
            if (common == null) common = t.type();
            else if (!compatible(common, t)) {
                d.add(E_TYPE_INCOMPARABLE, field, "case branches must share a type; got " + common.render() + " and " + t.render() + ".",
                        "Make every then/else value the same type.", "expected", common.render(), "actual", t.render());
                return null;
            }
        }
        return common == null ? literal(null, null, field) : Typed.of(common, nullable);
    }

    private @Nullable Typed sumAll(Expr.SumAllExpr s, String field) {
        if (previousAggregate == null) {
            d.add(E_SUMALL_INVALID_PLACEMENT, field,
                    "sumAll is only valid in a step that immediately follows an aggregate.",
                    "Place this step directly after the aggregate whose measure you want the total of.");
            return null;
        }
        var names = previousAggregate.measures().stream().map(Step.AggregateMeasure::as).toList();
        var measure = names.stream().filter(n -> TableType.key(n).equals(TableType.key(s.col()))).findFirst();
        if (measure.isEmpty()) {
            d.add(E_SUMALL_UNKNOWN_COLUMN, field + ".col",
                    "sumAll('" + s.col() + "') must name a measure of the preceding aggregate.",
                    "Measures available: " + Diags.list(names) + ".", "received", s.col(), "candidates", Diags.candidates(s.col(), names));
            return null;
        }
        ColumnType c = table.find(s.col()).orElse(null);
        return c == null ? null : Typed.of(c.type(), false);
    }

    private @Nullable Typed concat(Expr.ConcatExpr c, String field) {
        if (c.parts().isEmpty()) {
            d.add(E_INVALID_ARGUMENT, field + ".parts", "concat needs at least one part.", "List the columns or literals to join.");
            return null;
        }
        boolean allNullable = true;
        concatDepth++;
        try {
            for (int i = 0; i < c.parts().size(); i++) {
                Typed t = expr(c.parts().get(i), field + ".parts[" + i + "]");
                if (t == null) return null;
                allNullable &= t.nullable();
            }
        } finally {
            concatDepth--;
        }
        return Typed.of(ScalarType.STRING, allNullable);
    }

    private @Nullable Typed text(String op, Expr arg, String field) {
        Typed t = expr(arg, field + ".arg");
        if (t == null) return null;
        if (t.type() != null && !ScalarType.isText(t.type())) {
            d.add(E_TYPE_MISMATCH, field + ".arg", op + " works on text; got " + t.render() + ".",
                    "Apply " + op + " to a string or categorical column.",
                    "expected", "string | categorical", "actual", t.render());
            return null;
        }
        return new Typed(ScalarType.STRING, t.nullable(), null, Source.VALUE, null, null, t.formatted());
    }

    private @Nullable Typed splitPart(Expr.SplitPartExpr s, String field) {
        Typed t = text("splitPart", s.arg(), field);
        if (t == null) return null;
        if (s.sep() == null || s.sep().isEmpty()) {
            d.add(E_INVALID_ARGUMENT, field + ".sep", "splitPart needs a non-empty separator.", "Give the literal text to split on, e.g. \" \" or \"@\".");
            return null;
        }
        if (s.index() < 1) {
            d.add(E_INVALID_ARGUMENT, field + ".index", "splitPart index must be 1 or more; got " + s.index() + ".",
                    "Index 1 is the first piece.", "received", s.index());
            return null;
        }
        return Typed.of(ScalarType.STRING, true);
    }

    private @Nullable Typed toText(Expr.ToTextExpr e, String field) {
        Typed t = expr(e.arg(), field + ".arg");
        if (t == null) return null;
        Set<String> allowed = t.type() != null && ScalarType.isTemporal(t.type()) ? DATE_PATTERNS
                : t.type() != null && ScalarType.isNumeric(t.type()) ? NUMBER_PATTERNS : null;
        if (allowed == null) {
            d.add(E_TYPE_MISMATCH, field + ".arg", "toText formats numbers and dates; got " + t.render() + ".",
                    "Text is already text; drop the toText.", "expected", "number | currency | percent | date | datetime", "actual", t.render());
            return null;
        }
        if (!allowed.contains(e.pattern())) {
            d.add(E_INVALID_ARGUMENT, field + ".pattern", "'" + e.pattern() + "' is not an allowed toText pattern for " + t.render() + ".",
                    "Allowed patterns: " + Diags.list(allowed.stream().sorted().toList()) + ".", "received", e.pattern());
            return null;
        }
        // Outside a concat, this is a number or date stored as text: callers decide whether that's allowed.
        return new Typed(ScalarType.STRING, t.nullable(), null, Source.VALUE, null, null, concatDepth == 0);
    }

    // ── Predicates ─────────────────────────────────────────────────────────────

    void predicate(Predicate p, String field) {
        switch (p) {
            case Predicate.EqPredicate e -> compare("eq", e.left(), e.right(), false, field);
            case Predicate.NePredicate e -> compare("ne", e.left(), e.right(), false, field);
            case Predicate.LtPredicate e -> compare("lt", e.left(), e.right(), true, field);
            case Predicate.LtePredicate e -> compare("lte", e.left(), e.right(), true, field);
            case Predicate.GtPredicate e -> compare("gt", e.left(), e.right(), true, field);
            case Predicate.GtePredicate e -> compare("gte", e.left(), e.right(), true, field);
            case Predicate.InPredicate i -> membership("in", i.col(), i.values(), field);
            case Predicate.NotInPredicate i -> membership("notIn", i.col(), i.values(), field);
            case Predicate.IsNullPredicate n -> column(n.col(), field + ".col");
            case Predicate.IsNotNullPredicate n -> column(n.col(), field + ".col");
            case Predicate.AndPredicate a -> clauses("and", a.clauses(), field);
            case Predicate.OrPredicate o -> clauses("or", o.clauses(), field);
            case Predicate.NotPredicate n -> predicate(n.clause(), field + ".clause");
        }
    }

    private void clauses(String op, List<Predicate> clauses, String field) {
        if (clauses.isEmpty()) {
            d.add(E_INVALID_ARGUMENT, field + ".clauses", op + " needs at least one clause.", "Add the conditions to combine, or remove the " + op + ".");
            return;
        }
        for (int i = 0; i < clauses.size(); i++) predicate(clauses.get(i), field + ".clauses[" + i + "]");
    }

    @Nullable Typed valueRef(@Nullable Predicate.ValueRef v, String field) {
        if (v == null) return null;
        return switch (v) {
            case Predicate.ValueRef.ColRef c -> {
                ColumnType col = column(c.col(), field + ".col");
                yield col == null ? null : Typed.column(col);
            }
            case Predicate.ValueRef.Lit l -> literal(l.value(), l.valueType(), field);
            case Predicate.ValueRef.ParamRef p -> param(p.name(), field);
        };
    }

    private void compare(String op, Predicate.ValueRef lv, Predicate.ValueRef rv, boolean ordering, String field) {
        Typed l = valueRef(lv, field + ".left");
        Typed r = valueRef(rv, field + ".right");
        if (l == null || r == null) return;
        if (!comparable(op, l, r, field)) return;
        if (ordering) {
            Typed col = l.adaptable() ? r : l;
            if (col.type() != null && !col.adaptable() && !ScalarType.isOrdered(col.type())) {
                d.add(E_ORDER_COMPARISON_NON_ORDERED, field,
                        op + " needs an ordered type; " + col.render() + " has no order.",
                        "Use eq/ne/in for text and categories, or declare the category order in the semantic model.",
                        "actual", col.render());
                return;
            }
        }
        domainCheck(l, r, field);
        domainCheck(r, l, field);
    }

    private void membership(String op, String col, List<Object> values, String field) {
        ColumnType c = column(col, field + ".col");
        if (c == null) return;
        if (values == null || values.isEmpty()) {
            d.add(E_INVALID_ARGUMENT, field + ".values", op + " needs at least one value.", "List the values to match.");
            return;
        }
        Typed ct = Typed.column(c);
        for (int i = 0; i < values.size(); i++) {
            // In-lists are plain values; a date column is matched with ISO date strings.
            Object v = values.get(i);
            ValueType vt = ScalarType.isTemporal(c.type()) && v instanceof String ? ValueType.DATE : null;
            Typed lit = literal(v, vt, field + ".values[" + i + "]");
            if (lit == null) return;
            if (!comparable(op, ct, lit, field + ".values[" + i + "]")) return;
            domainCheck(ct, lit, field + ".values[" + i + "]");
        }
    }

    /** Warns when a literal isn't a known member of a categorical column's domain (compared case-insensitively). */
    private void domainCheck(Typed column, Typed other, String field) {
        if (column.type() instanceof CategoricalType cat && cat.members() != null
                && other.literal() && other.value() instanceof String s
                && cat.members().stream().noneMatch(m -> m.equalsIgnoreCase(s))) {
            d.add(W_CATEGORICAL_LITERAL_NOT_IN_DOMAIN, field,
                    "'" + s + "' is not a known value of " + cat.domain() + " {" + Diags.list(cat.members()) + "}.",
                    "Known values: " + Diags.list(cat.members()) + ". If '" + s + "' is new, the condition will match no rows today.",
                    "received", s, "candidates", Diags.candidates(s, cat.members()));
        }
    }

    // ── Compatibility rules ────────────────────────────────────────────────────

    /** ir-spec §4 comparability, plus adaptation of unit-less number literals and parameters. */
    boolean comparable(String op, Typed a, Typed b, String field) {
        if (a.type() == null || b.type() == null) return true;
        if (a.type() instanceof CurrencyType ca && b.type() instanceof CurrencyType cb && unitsDiffer(ca, cb)) {
            unitMismatch(op, ca, cb, field);
            return false;
        }
        if (compatible(a.type(), b) || compatible(b.type(), a)) return true;
        boolean textVsDate = (a.literal() && a.type() instanceof ScalarType.StringType && ScalarType.isTemporal(b.type()))
                || (b.literal() && b.type() instanceof ScalarType.StringType && ScalarType.isTemporal(a.type()));
        d.add(E_TYPE_INCOMPARABLE, field, "Cannot compare " + a.render() + " with " + b.render() + ".",
                textVsDate ? "A date literal must say so: { \"type\": \"lit\", \"value\": \"2025-01-01\", \"valueType\": \"date\" }."
                        : "Compare values of the same type; write numbers without quotes.",
                "expected", a.render(), "actual", b.render());
        return false;
    }

    /** Whether {@code other} can stand where a value of {@code base} is expected. */
    static boolean compatible(ScalarType base, Typed other) {
        ScalarType t = other.type();
        if (t == null) return true;
        if (other.adaptable()) {
            if (t instanceof NumberType) return ScalarType.isNumeric(base);
            if (t instanceof ScalarType.StringType) return ScalarType.isText(base);
        }
        return family(base).equals(family(t));
    }

    private static String family(ScalarType t) {
        return switch (t) {
            case NumberType n -> "number";
            case CurrencyType c -> "currency";
            case PercentType p -> "percent";
            case ScalarType.DateType dt -> "temporal";
            case ScalarType.DateTimeType dt -> "temporal";
            case ScalarType.StringType s -> "text";
            case CategoricalType c -> "text";
            case ScalarType.BooleanType b -> "boolean";
        };
    }

    static boolean isIsoDate(String s) {
        try {
            LocalDate.parse(s);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    static boolean isIsoDateTime(String s) {
        try {
            LocalDateTime.parse(s.endsWith("Z") ? s.substring(0, s.length() - 1) : s);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    static boolean unitsDiffer(CurrencyType a, CurrencyType b) {
        return a.unit() != null && b.unit() != null && !a.unit().equals(b.unit());
    }

    private @Nullable Typed unitMismatch(String op, CurrencyType a, CurrencyType b, String field) {
        d.add(E_CURRENCY_UNIT_MISMATCH, field, op + " mixes " + a.render() + " and " + b.render() + ".",
                "There is no implicit currency conversion. Use columns in the same currency.",
                "expected", a.render(), "actual", b.render());
        return null;
    }

    private @Nullable Typed incomparable(String op, Typed a, Typed b, String field) {
        d.add(E_TYPE_INCOMPARABLE, field, op + " cannot combine " + a.render() + " and " + b.render() + ".",
                "Arithmetic needs numeric operands with matching units: number with number, currency with the same currency, or currency with a plain number.",
                "expected", a.render(), "actual", b.render());
        return null;
    }

    private List<String> namesWhere(java.util.function.Predicate<ScalarType> test) {
        return table.columns().stream().filter(c -> test.test(c.type())).map(ColumnType::name).toList();
    }
}
