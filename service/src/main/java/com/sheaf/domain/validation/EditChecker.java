package com.sheaf.domain.validation;

import com.sheaf.domain.catalog.ColumnDependent;
import com.sheaf.domain.common.Nullable;
import com.sheaf.domain.ir.EditOp;
import com.sheaf.domain.ir.Plan;
import com.sheaf.domain.ir.Position;
import com.sheaf.domain.ir.ValueType;
import com.sheaf.domain.types.ColumnType;
import com.sheaf.domain.types.ScalarType;
import com.sheaf.domain.types.TableType;
import com.sheaf.domain.types.TypeEnvironment;
import com.sheaf.domain.validation.ExprTyper.Typed;
import com.sheaf.domain.validation.ImpactReport.AffectedDependent;
import com.sheaf.domain.validation.ImpactReport.Cause;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.sheaf.domain.validation.DiagnosticCode.*;

/**
 * Type-checks a bound edit plan and computes its {@link ImpactReport}: a fold over the operations
 * that tracks the target's table type (with each column's origin, so renames and moves keep their
 * identity) and records everything the plan would change before anything runs.
 *
 * <p>Whether to break or convert dependents is the user's decision, not the plan's: dependents are
 * reported ({@code W_DEPENDENTS_NEED_CONSENT}) and the commit's consent check refuses unless the
 * commit request says what to do with them.
 */
final class EditChecker {

    private final TypeEnvironment env;
    private final Diags d = new Diags();
    private Map<String, ValueType> params = Map.of();

    private final List<String> added = new ArrayList<>();
    private final Set<String> removed = new LinkedHashSet<>();
    private final Set<String> overwritten = new LinkedHashSet<>();
    private final List<ImpactReport.Rename> renamed = new ArrayList<>();
    private final Set<String> moved = new LinkedHashSet<>();
    private final Set<String> formulaOverwritten = new LinkedHashSet<>();
    private final List<AffectedDependent> needConsent = new ArrayList<>();
    private boolean removesRows;

    private EditChecker(TypeEnvironment env) {
        this.env = env;
    }

    static CheckResult check(Plan.EditPlan plan, TypeEnvironment env) {
        return new EditChecker(env).run(plan);
    }

    static String operator(EditOp op) {
        return switch (op) {
            case EditOp.AddColumn a -> "addColumn";
            case EditOp.SetColumn s -> "setColumn";
            case EditOp.DropColumn x -> "dropColumn";
            case EditOp.RenameColumn r -> "renameColumn";
            case EditOp.MoveColumn m -> "moveColumn";
            case EditOp.DropRows r -> "dropRows";
        };
    }

    private CheckResult run(Plan.EditPlan plan) {
        params = QueryChecker.declareParams(plan.params(), d);
        d.at(null, "target", "");
        var entity = env.entity(plan.target());
        if (entity.isEmpty()) {
            var names = env.entities().keySet();
            d.add(E_UNKNOWN_SOURCE, "target",
                    "'" + plan.target() + "' is not a known table." + Diags.didYouMean(plan.target(), names),
                    "Known tables: " + Diags.list(names.stream().sorted().toList()) + ".", "received", plan.target(),
                    "candidates", Diags.candidates(plan.target(), names));
            return new CheckResult(null, d.all(), null);
        }
        if (plan.ops().isEmpty()) {
            d.add(E_EDIT_EMPTY, "ops", "The edit plan has no operations.", "Add the column or row operations the request asks for.");
            return new CheckResult(null, d.all(), null);
        }

        TableType table = entity.get().tableType();
        Set<String> gone = new HashSet<>();
        for (int i = 0; i < plan.ops().size(); i++) {
            EditOp op = plan.ops().get(i);
            d.at(i, operator(op), "/ops/" + i);
            TableType next = op(op, table, gone, entity.get());
            if (next == null || d.stepFailed()) return new CheckResult(null, d.all(), null);
            for (String n : table.names()) if (!next.has(n)) gone.add(TableType.key(n));
            next.names().forEach(n -> gone.remove(TableType.key(n)));
            table = next;
        }

        d.at(null, "edit", "");
        if (!needConsent.isEmpty()) {
            var where = needConsent.stream().map(a -> a.dependent().location() + " (" + a.dependent().refClass() + ")").distinct().toList();
            d.add(W_DEPENDENTS_NEED_CONSENT, null,
                    needConsent.size() + (needConsent.size() == 1 ? " thing reads" : " things read") + " what this plan removes: "
                            + Diags.list(where) + ". The user must choose to cancel or to convert them to their current values.",
                    "No change to the plan is needed; the preview asks the user.", "dependents", needConsent);
        }
        boolean removesSomething = !removed.isEmpty() || removesRows;
        if (env.untraceableReferences() && removesSomething) {
            d.add(W_UNTRACEABLE_REFERENCES, null,
                    "This workbook has references Sheaf can't follow (INDIRECT, OFFSET or links to other workbooks), so it can't guarantee nothing else depends on what this plan removes.",
                    "Tell the user before they commit; the impact list may be incomplete.");
        }

        var impact = new ImpactReport(added, List.copyOf(removed), List.copyOf(overwritten), renamed, List.copyOf(moved),
                removesRows, List.copyOf(formulaOverwritten), needConsent, env.untraceableReferences());
        return new CheckResult(table, d.all(), impact);
    }

    private @Nullable TableType op(EditOp op, TableType t, Set<String> gone, TypeEnvironment.EntitySchema entity) {
        var x = new ExprTyper(t, gone, null, params, d);
        return switch (op) {
            case EditOp.AddColumn a -> addColumn(a, t, x);
            case EditOp.SetColumn s -> setColumn(s, t, x);
            case EditOp.DropColumn c -> dropColumn(c, t, entity);
            case EditOp.RenameColumn r -> renameColumn(r, t);
            case EditOp.MoveColumn m -> moveColumn(m, t);
            case EditOp.DropRows r -> dropRows(r, t, x, entity);
        };
    }

    private @Nullable ColumnType existing(TableType t, String name, String field) {
        var c = t.find(name);
        if (c.isPresent()) return c.get();
        d.add(E_EDIT_UNKNOWN_COLUMN, field, "The table has no column '" + name + "'." + Diags.didYouMean(name, t.names()),
                "Columns now: " + Diags.list(t.names()) + ".", "received", name, "suggestion", Diags.closest(name, t.names()),
                "candidates", Diags.candidates(name, t.names()));
        return null;
    }

    /** Index at which a column goes, or -1 after a diagnostic. {@code self} is the column being moved, if any. */
    private int insertIndex(Position p, List<ColumnType> cols, TableType t, @Nullable String self) {
        return switch (p) {
            case Position.First f -> 0;
            case Position.Last l -> cols.size();
            case Position.After a -> {
                if (self != null && TableType.key(a.column()).equals(TableType.key(self))) {
                    d.add(E_INVALID_ARGUMENT, "position.column", "A column can't be placed after itself.", "Name the column it should follow.");
                    yield -1;
                }
                if (existing(t, a.column(), "position.column") == null) yield -1;
                for (int i = 0; i < cols.size(); i++) if (TableType.key(cols.get(i).name()).equals(TableType.key(a.column()))) yield i + 1;
                yield -1;
            }
        };
    }

    private @Nullable TableType addColumn(EditOp.AddColumn a, TableType t, ExprTyper x) {
        if (t.has(a.as())) {
            d.add(E_EDIT_COLUMN_COLLISION, "as", "A column named '" + a.as() + "' already exists (names ignore case, as in Excel).",
                    "Pick a new name, or use setColumn to overwrite '" + a.as() + "'.", "received", a.as());
            return null;
        }
        Typed e = x.expr(a.expr(), "expr");
        if (e == null || QueryChecker.refuseFormatted(e, d, "expr")) return null;
        var cols = new ArrayList<>(t.columns());
        int at = insertIndex(a.position(), cols, t, null);
        if (at < 0) return null;
        cols.add(at, ColumnType.computed(a.as(), e.type() == null ? ScalarType.STRING : e.type(), e.nullable(), e.cardinality(), null));
        added.add(a.as());
        return TableType.of(cols);
    }

    private @Nullable TableType setColumn(EditOp.SetColumn s, TableType t, ExprTyper x) {
        ColumnType c = existing(t, s.col(), "col");
        if (c == null) return null;
        if (s.where() != null) x.predicate(s.where(), "where");
        Typed e = x.expr(s.expr(), "expr");
        if (e == null || QueryChecker.refuseFormatted(e, d, "expr")) return null;
        if (!ExprTyper.compatible(c.type(), e)) {
            d.add(E_TYPE_MISMATCH, "expr", "'" + c.name() + "' holds " + c.type().render() + " but the new value is " + e.render() + ".",
                    "Write a value of the column's type, or add a new column instead.", "expected", c.type().render(), "actual", e.render());
            return null;
        }
        if (e.type() instanceof ScalarType.CurrencyType ce && c.type() instanceof ScalarType.CurrencyType cc && ExprTyper.unitsDiffer(cc, ce)) {
            d.add(E_CURRENCY_UNIT_MISMATCH, "expr", "'" + c.name() + "' is " + cc.render() + " but the new value is " + ce.render() + ".",
                    "There is no implicit currency conversion.", "expected", cc.render(), "actual", ce.render());
            return null;
        }
        if (c.formula()) {
            d.add(W_OVERWRITES_FORMULAS, "col", "'" + c.name() + "' is calculated by formulas; this replaces them with fixed values.",
                    "Confirm with the user: after this, '" + c.name() + "' will no longer recalculate.", "column", c.name());
        }
        if (c.origin() != null) {
            overwritten.add(c.origin().column());
            if (c.formula()) formulaOverwritten.add(c.origin().column());
        }
        var cols = new ArrayList<>(t.columns());
        cols.set(t.indexOf(c.name()), c.withNullable(c.nullable() || e.nullable()));
        return TableType.of(cols);
    }

    private @Nullable TableType dropColumn(EditOp.DropColumn drop, TableType t, TypeEnvironment.EntitySchema entity) {
        ColumnType c = existing(t, drop.col(), "col");
        if (c == null) return null;
        if (t.columns().size() == 1) {
            d.add(E_EDIT_DROPS_EVERY_COLUMN, "col", "This would remove the table's last column.",
                    "Keep at least one column; to remove the whole table, delete the sheet in Excel.");
            return null;
        }
        if (c.origin() != null) {
            // Every class needs consent: exclusive and whole-column references become #REF!, spanning and
            // whole-row references shrink and silently change value.
            for (ColumnDependent dep : dependents(entity, c.origin().column())) {
                needConsent.add(new AffectedDependent(c.origin().column(), dep, Cause.columnRemoved));
            }
            removed.add(c.origin().column());
        } else {
            added.remove(c.name());
        }
        var cols = new ArrayList<>(t.columns());
        cols.remove(t.indexOf(c.name()));
        return TableType.of(cols);
    }

    private @Nullable TableType dropRows(EditOp.DropRows r, TableType t, ExprTyper x, TypeEnvironment.EntitySchema entity) {
        x.predicate(r.where(), "where");
        // Whole-column and whole-range readers are meant to change when rows go (that is the point of
        // deleting them). Readers that name particular rows can become #REF! or shrink silently.
        for (var col : entity.columns()) {
            for (ColumnDependent dep : col.dependents()) {
                if (dep.fixedRows() && needConsent.stream().noneMatch(a -> a.dependent().equals(dep) && a.cause() == Cause.rowsRemoved)) {
                    needConsent.add(new AffectedDependent(col.name(), dep, Cause.rowsRemoved));
                }
            }
        }
        removesRows = true;
        return t;
    }

    private static List<ColumnDependent> dependents(TypeEnvironment.EntitySchema entity, String column) {
        return entity.column(column).map(TypeEnvironment.ColumnSchema::dependents).orElse(List.of());
    }

    private @Nullable TableType renameColumn(EditOp.RenameColumn r, TableType t) {
        ColumnType c = existing(t, r.col(), "col");
        if (c == null) return null;
        if (r.to() == null || r.to().isBlank()) {
            d.add(E_INVALID_ARGUMENT, "to", "The new name is empty.", "Give the new column name.");
            return null;
        }
        if (r.to().equals(c.name())) return t;
        // A case-only rename ("name" → "Name") is fine; any other existing name, ignoring case, collides.
        if (t.has(r.to()) && !TableType.key(r.to()).equals(TableType.key(c.name()))) {
            d.add(E_RENAME_COLLISION, "to", "A column named '" + r.to() + "' already exists (names ignore case, as in Excel).",
                    "Pick a different name, or drop the existing column first.", "received", r.to());
            return null;
        }
        if (c.origin() != null) renamed.add(new ImpactReport.Rename(c.origin().column(), r.to()));
        else added.set(added.indexOf(c.name()), r.to());
        var cols = new ArrayList<>(t.columns());
        cols.set(t.indexOf(c.name()), c.withName(r.to()));
        return TableType.of(cols);
    }

    private @Nullable TableType moveColumn(EditOp.MoveColumn m, TableType t) {
        ColumnType c = existing(t, m.col(), "col");
        if (c == null) return null;
        var cols = new ArrayList<>(t.columns());
        cols.remove(t.indexOf(c.name()));
        int at = insertIndex(m.position(), cols, t, c.name());
        if (at < 0) return null;
        cols.add(at, c);
        if (c.origin() != null) moved.add(c.origin().column());
        return TableType.of(cols);
    }
}
