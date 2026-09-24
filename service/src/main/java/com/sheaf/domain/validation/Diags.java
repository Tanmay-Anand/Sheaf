package com.sheaf.domain.validation;

import com.sheaf.domain.common.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Collects diagnostics for the step currently being checked. */
final class Diags {

    private static final int MAX_CANDIDATES = 5;

    private final List<Diagnostic> all = new ArrayList<>();
    private @Nullable Integer step;
    private @Nullable String operator;
    private String base = "";
    private int errorsAtStepStart;

    /**
     * @param base JSON Pointer of the thing being checked, e.g. {@code /steps/2}, {@code /ops/0},
     *             {@code /sink}, or "" for the plan itself.
     */
    void at(@Nullable Integer step, @Nullable String operator, String base) {
        this.step = step;
        this.operator = operator;
        this.base = base;
        this.errorsAtStepStart = errorCount();
    }

    /** @param field Path inside the current step in dotted form, e.g. {@code measures[0].of}; null for the step itself. */
    void add(DiagnosticCode code, @Nullable String field, String message, String hint, Object... context) {
        addAt(code, pointer(base, field), message, hint, context);
    }

    /** A finding about something other than the current step, e.g. the project column a sink rejects. */
    void addAt(DiagnosticCode code, String pointer, String message, String hint, Object... context) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        for (int i = 0; i + 1 < context.length; i += 2) {
            if (context[i + 1] != null) ctx.put((String) context[i], context[i + 1]);
        }
        all.add(new Diagnostic(code, pointer, step, operator, message, hint, ctx));
    }

    int errorCount() {
        return (int) all.stream().filter(Diagnostic::isError).count();
    }

    /** Whether the current step added any error. */
    boolean stepFailed() {
        return errorCount() > errorsAtStepStart;
    }

    List<Diagnostic> all() {
        return all;
    }

    // ── JSON Pointer ───────────────────────────────────────────────────────────

    /** "measures[0].of" under "/steps/2" → "/steps/2/measures/0/of" (RFC 6901, with ~ and / escaped). */
    static String pointer(String base, @Nullable String field) {
        if (field == null || field.isEmpty()) return base;
        var sb = new StringBuilder(base);
        for (String part : field.replaceAll("\\[(\\d+)]", ".$1").split("\\.")) {
            if (part.isEmpty()) continue;
            sb.append('/').append(part.replace("~", "~0").replace("/", "~1"));
        }
        return sb.toString();
    }

    // ── Suggestions ────────────────────────────────────────────────────────────

    /** Up to five candidates, closest first: what the model should have written instead. */
    static List<String> candidates(String wanted, Collection<String> names) {
        String w = wanted.toLowerCase();
        return names.stream()
                .sorted(Comparator.<String>comparingInt(n -> distance(w, n.toLowerCase())).thenComparing(n -> n))
                .limit(MAX_CANDIDATES)
                .toList();
    }

    /** The closest candidate by edit distance, when it's close enough to be a likely typo. */
    static @Nullable String closest(String wanted, Collection<String> candidates) {
        String best = null;
        int bestD = Integer.MAX_VALUE;
        for (String c : candidates) {
            int d = distance(wanted.toLowerCase(), c.toLowerCase());
            if (d < bestD) {
                bestD = d;
                best = c;
            }
        }
        int limit = Math.max(2, wanted.length() / 3);
        return best != null && bestD <= limit ? best : null;
    }

    static String didYouMean(String wanted, Collection<String> candidates) {
        String c = closest(wanted, candidates);
        return c == null ? "" : " Did you mean '" + c + "'?";
    }

    static String list(Collection<String> names) {
        return String.join(", ", names);
    }

    private static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }
}
