package com.sheaf.domain.validation;

import com.sheaf.domain.common.Nullable;
import com.sheaf.domain.ir.Bindings;
import com.sheaf.domain.ir.Plan;
import com.sheaf.domain.ir.Sink;
import com.sheaf.domain.ir.SinkIntent;
import com.sheaf.domain.ir.Step;
import com.sheaf.domain.ir.UnboundPlan;
import com.sheaf.domain.types.TableType;
import com.sheaf.domain.types.TypeEnvironment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.sheaf.domain.validation.DiagnosticCode.*;

/**
 * Unbound plan (what the model wrote) + environment → bound plan (what gets checked, hashed and run).
 *
 * <p>Binding resolves every table the plan names to its stable id and records the id of each of its
 * columns, turns the sink intent into a concrete sink (a valid, unused new-sheet name; a template's
 * header rows), and normalises table names to their catalog spelling so that "orders" and "Orders"
 * produce the same plan. It never decides anything the user must decide.
 */
public final class Binder {

    static final String DEFAULT_SHEET_NAME = "Sheaf result";

    private Binder() {}

    public record BindResult(@Nullable Plan plan, List<Diagnostic> diagnostics) {
        public BindResult {
            diagnostics = List.copyOf(diagnostics);
        }
    }

    public static BindResult bind(UnboundPlan unbound, TypeEnvironment env) {
        var d = new Diags();
        return switch (unbound) {
            case UnboundPlan.UnboundQuery q -> bindQuery(q, env, d);
            case UnboundPlan.UnboundEdit e -> bindEdit(e, env, d);
        };
    }

    private static BindResult bindQuery(UnboundPlan.UnboundQuery q, TypeEnvironment env, Diags d) {
        d.at(null, "source", "");
        var source = resolve(q.source(), "source", env, d);
        if (source == null) return new BindResult(null, d.all());

        // Every table the plan reads: the source plus each join/lookup target that exists. Unknown
        // targets are left to the type checker, which reports them against the step.
        Map<String, TypeEnvironment.EntitySchema> used = new LinkedHashMap<>();
        used.put(source.id(), source);
        List<Step> steps = new ArrayList<>();
        for (Step s : q.steps()) {
            Step bound = switch (s) {
                case Step.JoinStep j -> {
                    var with = env.entity(j.with());
                    with.ifPresent(w -> used.putIfAbsent(w.id(), w));
                    yield with.map(w -> (Step) new Step.JoinStep(w.name(), j.on(), j.kind())).orElse(j);
                }
                case Step.LookupStep l -> {
                    var with = env.entity(l.with());
                    with.ifPresent(w -> used.putIfAbsent(w.id(), w));
                    yield with.map(w -> (Step) new Step.LookupStep(w.name(), l.on(), l.take())).orElse(l);
                }
                default -> s;
            };
            steps.add(bound);
        }

        d.at(null, "sink", "/sink");
        Sink sink = switch (q.sink()) {
            case SinkIntent.NewSheetIntent n -> new Sink.NewSheetSink(sheetName(n.name(), env.sheetNames()), "A1");
            case SinkIntent.AnchorIntent a -> new Sink.AnchorSink();
            case SinkIntent.TemplateIntent t -> {
                var schema = env.templates().get(t.templateId());
                if (schema == null) {
                    var ids = env.templates().keySet();
                    d.add(E_TEMPLATE_UNKNOWN, "templateId", "'" + t.templateId() + "' is not an imported template." + Diags.didYouMean(t.templateId(), ids),
                            "Imported templates: " + Diags.list(ids.stream().sorted().toList()) + ".", "received", t.templateId(),
                            "candidates", Diags.candidates(t.templateId(), ids));
                    yield null;
                }
                yield new Sink.TemplateSink(schema.templateId(), schema.headerRow(), schema.firstDataRow());
            }
        };
        if (sink == null) return new BindResult(null, d.all());
        return new BindResult(new Plan.QueryPlan(source.name(), steps, sink, q.params(), bindings(used.values())), d.all());
    }

    private static BindResult bindEdit(UnboundPlan.UnboundEdit e, TypeEnvironment env, Diags d) {
        d.at(null, "target", "");
        var target = resolve(e.target(), "target", env, d);
        if (target == null) return new BindResult(null, d.all());
        return new BindResult(new Plan.EditPlan(target.name(), e.ops(), e.params(), bindings(List.of(target))), d.all());
    }

    private static @Nullable TypeEnvironment.EntitySchema resolve(String name, String field, TypeEnvironment env, Diags d) {
        var e = env.entity(name);
        if (e.isPresent()) return e.get();
        var names = env.entities().keySet();
        d.add(E_UNKNOWN_SOURCE, field, "'" + name + "' is not a known table." + Diags.didYouMean(name, names),
                "Known tables: " + Diags.list(names.stream().sorted().toList()) + ".", "received", name,
                "candidates", Diags.candidates(name, names));
        return null;
    }

    private static Bindings bindings(Iterable<TypeEnvironment.EntitySchema> entities) {
        List<Bindings.EntityBinding> out = new ArrayList<>();
        for (var e : entities) {
            out.add(new Bindings.EntityBinding(e.name(), e.id(), e.sheetId(),
                    e.columns().stream().map(c -> new Bindings.ColumnBinding(c.name(), c.id())).toList()));
        }
        return new Bindings(out);
    }

    /**
     * A valid Excel sheet name, unused in the workbook: forbidden characters dropped, trimmed to 31
     * characters, and " 2", " 3"… appended on a (case-insensitive) collision.
     */
    static String sheetName(@Nullable String wanted, Set<String> existing) {
        String base = wanted == null ? "" : wanted.replaceAll("[\\[\\]:*?/\\\\]", "").strip();
        while (base.startsWith("'")) base = base.substring(1);
        while (base.endsWith("'")) base = base.substring(0, base.length() - 1);
        if (base.isBlank() || base.equalsIgnoreCase("History")) base = DEFAULT_SHEET_NAME;
        base = truncate(base, QueryChecker.SHEET_NAME_MAX);
        var taken = existing.stream().map(TableType::key).toList();
        String name = base;
        for (int i = 2; taken.contains(TableType.key(name)); i++) {
            String suffix = " " + i;
            name = truncate(base, QueryChecker.SHEET_NAME_MAX - suffix.length()) + suffix;
        }
        return name;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max).strip();
    }
}
