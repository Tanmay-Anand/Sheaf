package com.sheaf.application.planning;

import com.sheaf.domain.catalog.CatalogColumn;
import com.sheaf.domain.catalog.CatalogEntity;
import com.sheaf.domain.catalog.WorkbookCatalog;
import com.sheaf.domain.types.ScalarType;
import com.sheaf.domain.types.TypeEnvironment;
import com.sheaf.domain.validation.Diagnostic;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The planner's prompt: a versioned instruction artifact (resources/prompts/) plus a description of
 * the workbook built from its type environment. The description carries names, types and flags, the
 * values of small categories, and (only when the user allows them) up to five sample values per
 * column. Never rows.
 */
public final class PlannerPrompt {

    public static final String VERSION = "planner-v5";

    private static final String INSTRUCTIONS = load("/prompts/" + VERSION + ".md");
    private static final int MAX_MEMBERS = 30;

    private PlannerPrompt() {}

    private static String load(String path) {
        try (InputStream in = PlannerPrompt.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("Missing prompt " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String instructions() {
        return INSTRUCTIONS;
    }

    /** Sample values the user allowed the catalog to keep, by table then column. */
    public static Map<String, Map<String, List<String>>> exemplars(WorkbookCatalog catalog) {
        Map<String, Map<String, List<String>>> out = new LinkedHashMap<>();
        for (CatalogEntity e : catalog.entities()) {
            Map<String, List<String>> cols = new LinkedHashMap<>();
            for (CatalogColumn c : e.columns()) if (c.exemplars() != null && !c.exemplars().isEmpty()) cols.put(c.name(), c.exemplars());
            if (!cols.isEmpty()) out.put(e.name(), cols);
        }
        return out;
    }

    public static String system(TypeEnvironment env, Map<String, Map<String, List<String>>> exemplars) {
        return INSTRUCTIONS + "\n\n<workbook>\n" + describe(env, exemplars) + "</workbook>\n";
    }

    static String type(ScalarType t) {
        return t.render();
    }

    /** The workbook, as the model sees it. */
    public static String describe(TypeEnvironment env, Map<String, Map<String, List<String>>> exemplars) {
        var sb = new StringBuilder();
        sb.append("Tables (each column: name: type, then flags):\n");
        for (var e : env.entities().values()) {
            sb.append("- ").append(e.name()).append('\n');
            var samples = exemplars.getOrDefault(e.name(), Map.of());
            for (var c : e.columns()) {
                sb.append("    ").append(c.name()).append(": ").append(type(c.type()));
                List<String> flags = new ArrayList<>();
                if (c.nullable()) flags.add("may be blank");
                if (c.unique()) flags.add("key");
                if (c.formula()) flags.add("formulas");
                if (c.mayContainErrors()) flags.add("has error cells");
                if (c.numbersStoredAsText()) flags.add("numbers stored as text");
                if (!c.dependents().isEmpty()) {
                    var where = c.dependents().stream().limit(3).map(d -> d.location()).toList();
                    flags.add("read by " + c.dependents().size() + " (" + String.join(", ", where) + (c.dependents().size() > 3 ? ", …" : "") + ")");
                }
                if (c.cardinality() != null && !(c.type() instanceof ScalarType.NumberType)) flags.add(c.cardinality() + " distinct");
                if (c.type() instanceof ScalarType.CategoricalType cat) {
                    if (cat.members() != null && cat.members().size() <= MAX_MEMBERS) {
                        flags.add((cat.ordering() == ScalarType.Ordering.declared ? "ordered values " : "values ") + quoted(cat.members()));
                    } else if (cat.ordering() == ScalarType.Ordering.missing) {
                        flags.add("order not declared: can't be sorted");
                    }
                }
                var sample = samples.get(c.name());
                if (sample != null && !(c.type() instanceof ScalarType.CategoricalType cat2 && cat2.members() != null)) {
                    flags.add("e.g. " + quoted(sample));
                }
                if (!flags.isEmpty()) sb.append(" [").append(String.join("; ", flags)).append(']');
                sb.append('\n');
            }
        }
        var approved = env.joins().stream().filter(TypeEnvironment.JoinEdge::approved).toList();
        sb.append("\nApproved links (join or lookup only along these):\n");
        if (approved.isEmpty()) sb.append("- none yet\n");
        for (var j : approved) {
            sb.append("- ").append(j.fromEntity()).append('.').append(j.fromColumn()).append(" = ")
                    .append(j.toEntity()).append('.').append(j.toColumn()).append('\n');
        }
        if (!env.metrics().isEmpty()) {
            sb.append("\nMetrics:\n");
            for (var m : env.metrics().values()) {
                sb.append("- ").append(m.name()).append(" = ").append(m.fn()).append('(').append(m.of()).append(") on ").append(m.entity())
                        .append(m.filters().isEmpty() ? "" : " (with its own filters)").append('\n');
            }
        }
        if (!env.timeDimensions().isEmpty()) {
            sb.append("\nTime dimensions:\n");
            for (var t : env.timeDimensions().values()) {
                sb.append("- ").append(t.name()).append(" = ").append(t.entity()).append('.').append(t.column()).append(", grains ")
                        .append(t.grains().stream().map(Enum::name).sorted().collect(Collectors.joining(", "))).append('\n');
            }
        }
        if (!env.templates().isEmpty()) {
            sb.append("\nTemplates (fill with a query ending in project, columns exactly these, in order):\n");
            for (var t : env.templates().values()) {
                sb.append("- ").append(t.templateId()).append(" (\"").append(t.name()).append("\"")
                        .append(sameHeadersAs(t, env).map(e -> ", headers identical to " + e + "'s column names").orElse(""))
                        .append("): ");
                sb.append(t.columns().stream().map(c -> {
                    String s = c.name();
                    if (c.formula()) return s + " [formula: don't write]";
                    List<String> f = new ArrayList<>();
                    if (c.expected() != null) f.add(c.expected().render());
                    if (c.validationList() != null) f.add("one of " + quoted(c.validationList()));
                    return f.isEmpty() ? s : s + " [" + String.join("; ", f) + "]";
                }).collect(Collectors.joining(", "))).append('\n');
            }
        }
        sb.append("\nExisting sheet names: ").append(String.join(", ", env.sheetNames())).append('\n');
        return sb.toString();
    }

    /** A table whose columns include every writable template header by exact name, if one does. */
    private static java.util.Optional<String> sameHeadersAs(TypeEnvironment.TemplateSchema t, TypeEnvironment env) {
        var headers = t.columns().stream().filter(c -> !c.formula()).map(TypeEnvironment.TemplateColumn::name).toList();
        return env.entities().values().stream()
                .filter(e -> headers.stream().allMatch(h -> e.columns().stream().anyMatch(c -> c.name().equals(h))))
                .map(TypeEnvironment.EntitySchema::name)
                .findFirst();
    }

    private static String quoted(List<String> values) {
        return values.stream().limit(MAX_MEMBERS).map(v -> "\"" + v.replace("\"", "'") + "\"").collect(Collectors.joining(", "));
    }

    /** What goes back to the model when its plan didn't check: every error, with where and how to fix it. */
    static String repair(List<Diagnostic> errors) {
        var sb = new StringBuilder("That plan was rejected. Fix every error below and reply with the complete corrected JSON object only. ")
                .append("Pointers are relative to the plan (\"/steps/0/...\"), or to the whole reply for parse errors.\n");
        for (Diagnostic d : errors) {
            sb.append("- ").append(d.code()).append(" at ").append(d.pointer().isEmpty() ? "/" : d.pointer()).append(": ")
                    .append(d.message()).append(" Fix: ").append(d.hint());
            Object candidates = d.context().get("candidates");
            if (candidates instanceof List<?> list && !list.isEmpty()) sb.append(" Candidates: ").append(list);
            sb.append('\n');
        }
        return sb.toString();
    }
}
