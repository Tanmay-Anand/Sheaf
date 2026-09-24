package com.sheaf.domain.validation;

import com.sheaf.domain.commit.CommitRequest;
import com.sheaf.domain.ir.ParamDecl;
import com.sheaf.domain.ir.Plan;
import com.sheaf.domain.ir.Sink;
import com.sheaf.domain.ir.ValueType;
import com.sheaf.domain.types.TableType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static com.sheaf.domain.validation.DiagnosticCode.*;

/**
 * The last gate before a commit: does the user's {@link CommitRequest} supply everything the checked
 * plan needs from them? It never changes the plan; it only refuses.
 *
 * <p>The committer (in the pane) additionally re-hashes the previewed regions and refuses with
 * "the workbook changed since the preview"; that needs the live workbook, so it isn't here.
 */
public final class ConsentCheck {

    private static final Pattern CELL = Pattern.compile("^\\$?[A-Za-z]{1,3}\\$?[1-9]\\d{0,6}$");

    private ConsentCheck() {}

    /**
     * @param planHash The hash of {@code plan}'s envelope, as returned when it was checked.
     * @param checked  The result of type-checking {@code plan}; it must be valid.
     */
    public static List<Diagnostic> check(Plan plan, String planHash, CheckResult checked, CommitRequest req) {
        var d = new Diags();
        d.at(null, "commit", "");
        if (!checked.valid()) throw new IllegalArgumentException("Only a valid plan can be committed.");

        if (!planHash.equals(req.planHash())) {
            d.add(E_COMMIT_PLAN_MISMATCH, "planHash", "The commit is for a different plan than the one previewed.",
                    "Preview the plan again and commit that.", "expected", planHash, "actual", req.planHash());
            return d.all();
        }

        if (plan instanceof Plan.QueryPlan q && q.sink() instanceof Sink.AnchorSink) {
            if (req.anchor() == null) {
                d.add(E_SINK_ANCHOR_MISSING, "anchor", "This plan writes into an existing sheet, but no location was chosen.",
                        "Ask the user where the result should start.");
            } else if (!CELL.matcher(req.anchor().address()).matches()) {
                d.add(E_SINK_ANCHOR_INVALID, "anchor.address", "'" + req.anchor().address() + "' is not a single cell.",
                        "Choose the top-left cell of the output, e.g. B2.", "received", req.anchor().address());
            }
        }

        var impact = checked.impact();
        if (impact != null && !impact.dependentsNeedingConsent().isEmpty() && req.onDependents() == CommitRequest.OnDependents.block) {
            var where = impact.dependentsNeedingConsent().stream()
                    .map(a -> a.dependent().location() + " (" + a.dependent().refClass() + ")").distinct().toList();
            d.add(E_DROP_HAS_DEPENDENTS, "onDependents",
                    "What this plan removes is still used by " + Diags.list(where) + ". Removing it would break or silently change "
                            + (where.size() == 1 ? "it." : "them."),
                    "Ask the user to cancel, or to convert those to their current values (onDependents: convertToValues).",
                    "dependents", impact.dependentsNeedingConsent());
        }

        params(plan.params(), req.params(), d);
        return d.all();
    }

    private static void params(List<ParamDecl> declared, List<CommitRequest.ParamValue> given, Diags d) {
        Map<String, Object> values = new HashMap<>();
        Map<String, Boolean> present = new HashMap<>();
        for (var p : given) {
            values.put(TableType.key(p.name()), p.value());
            present.put(TableType.key(p.name()), true);
        }
        for (var p : declared) {
            String k = TableType.key(p.name());
            if (!present.containsKey(k)) {
                d.add(E_PARAM_MISSING, "params", "The plan needs a value for '" + p.name() + "' (" + p.valueType().wire() + ").",
                        "Supply it in the commit request.", "expected", p.valueType().wire());
                continue;
            }
            Object v = values.get(k);
            if (!matches(v, p.valueType())) {
                d.add(E_PARAM_TYPE, "params", "'" + p.name() + "' must be a " + p.valueType().wire() + "; got " + v + ".",
                        p.valueType() == ValueType.DATE ? "Give dates as yyyy-mm-dd." : "Give a value of the declared type.",
                        "expected", p.valueType().wire(), "actual", String.valueOf(v));
            }
        }
        for (var p : given) {
            if (declared.stream().noneMatch(x -> TableType.key(x.name()).equals(TableType.key(p.name())))) {
                d.add(E_UNKNOWN_PARAM, "params", "The plan declares no parameter '" + p.name() + "'.",
                        "Only send values for declared parameters.", "received", p.name(),
                        "candidates", Diags.candidates(p.name(), declared.stream().map(ParamDecl::name).toList()));
            }
        }
    }

    private static boolean matches(Object v, ValueType t) {
        return switch (t) {
            case STRING -> v instanceof String;
            case NUMBER -> v instanceof Number;
            case BOOLEAN -> v instanceof Boolean;
            case DATE -> v instanceof String s && ExprTyper.isIsoDate(s);
            case DATETIME -> v instanceof String s && ExprTyper.isIsoDateTime(s);
        };
    }
}
