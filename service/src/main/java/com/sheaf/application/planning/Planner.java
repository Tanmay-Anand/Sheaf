package com.sheaf.application.planning;

import com.sheaf.application.CheckReport;
import com.sheaf.domain.common.Nullable;
import com.sheaf.domain.ir.PlannerResponse;
import com.sheaf.domain.types.TypeEnvironment;
import com.sheaf.domain.validation.Diagnostic;
import com.sheaf.domain.validation.PlanParser;
import com.sheaf.domain.validation.TypeChecker;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Question → checked plan. The model writes; Sheaf parses, binds and type-checks; every error goes
 * back to the model with its pointer and hint, at most {@link #MAX_REPAIRS} times. The loop always
 * terminates: a plan that still fails after the last repair is reported, never run.
 *
 * <p>The model's authority ends at the plan. Binding resolves only names the catalog has, and sinks
 * are intents, so no answer (however a hostile cell value tried to steer it) can read outside the
 * catalogued tables or write anywhere the user didn't choose.
 */
public final class Planner {

    public static final int MAX_REPAIRS = 2;
    static final int MAX_TOKENS = 2_500;
    static final double TEMPERATURE = 0.0;

    private final LanguageModelPort model;

    public Planner(LanguageModelPort model) {
        this.model = model;
    }

    public enum Kind { plan, clarify, refuse, explain, failed }

    /** Everything recorded about a run: which model saw what, what it cost, what it produced. */
    /** @param endpoint Where the question and workbook description were sent (a base URL, never a key). */
    public record Run(String provider, String endpoint, String model, String promptVersion, int calls, int promptTokens,
                      int completionTokens, @Nullable Double cost, @Nullable String planHash) {}

    /**
     * @param report     For a plan: the checked, bound plan (valid). For a failed plan: the last check.
     * @param diagnostics For failed: why the last attempt was refused.
     * @param repairs     Why each rejected attempt was sent back, e.g. "E_UNKNOWN_COLUMN /steps/1/of".
     * @param answer      For explain: the answer about the workbook.
     * @param sent        Exactly what described the workbook to the model (no rows), so the user can see it.
     */
    public record Outcome(
            Kind kind,
            @Nullable CheckReport report,
            @Nullable PlannerResponse.Annotations annotations,
            @Nullable String question,
            @Nullable List<String> options,
            @Nullable String understood,
            @Nullable List<String> closestSupported,
            List<Diagnostic> diagnostics,
            List<String> repairs,
            @Nullable String answer,
            String sent,
            Run run
    ) {}

    /**
     * @param exemplars Sample values the user allowed (may be empty); never rows.
     * @param modelId   The provider's model id, chosen per request.
     */
    public Outcome plan(String question, TypeEnvironment env, Map<String, Map<String, List<String>>> exemplars, String modelId) {
        String description = PlannerPrompt.describe(env, exemplars);
        String system = PlannerPrompt.system(env, exemplars);
        List<LanguageModelPort.Message> messages = new ArrayList<>();
        messages.add(new LanguageModelPort.Message("user", question));

        int calls = 0;
        int in = 0;
        int out = 0;
        Double cost = null;
        String answeredBy = modelId;
        List<Diagnostic> lastErrors = List.of();
        List<String> repairs = new ArrayList<>();
        CheckReport lastReport = null;

        for (int attempt = 0; attempt <= MAX_REPAIRS; attempt++) {
            var reply = model.complete(new LanguageModelPort.Request(modelId, system, messages, MAX_TOKENS, TEMPERATURE));
            calls++;
            in += reply.promptTokens();
            out += reply.completionTokens();
            if (reply.cost() != null) cost = (cost == null ? 0 : cost) + reply.cost();
            answeredBy = reply.model();
            messages.add(new LanguageModelPort.Message("assistant", reply.text()));

            var parsed = PlanParser.parseResponse(extractJson(reply.text()));
            if (parsed.value() == null) {
                lastErrors = parsed.diagnostics();
                lastReport = null;
                repairs.add(summary(lastErrors));
                messages.add(new LanguageModelPort.Message("user", PlannerPrompt.repair(lastErrors)));
                continue;
            }
            Run run = new Run(model.provider(), model.endpoint(), answeredBy, PlannerPrompt.VERSION, calls, in, out, cost, null);
            switch (parsed.value()) {
                case PlannerResponse.ClarifyResponse c -> {
                    return new Outcome(Kind.clarify, null, null, c.question(), c.options(), null, null, List.of(), repairs, null, description, run);
                }
                case PlannerResponse.RefuseResponse r -> {
                    return new Outcome(Kind.refuse, null, null, null, null, r.understood(), r.closestSupported(), List.of(), repairs, null, description, run);
                }
                case PlannerResponse.ExplainResponse x -> {
                    return new Outcome(Kind.explain, null, null, null, null, null, null, List.of(), repairs, x.answer(), description, run);
                }
                case PlannerResponse.PlanResponse p -> {
                    var checked = TypeChecker.bindAndCheck(p.plan(), env);
                    var report = CheckReport.of(checked);
                    if (report.valid()) {
                        run = new Run(model.provider(), model.endpoint(), answeredBy, PlannerPrompt.VERSION, calls, in, out, cost, report.planHash());
                        return new Outcome(Kind.plan, report, p.annotations(), null, null, null, null, List.of(), repairs, null, description, run);
                    }
                    lastReport = report;
                    lastErrors = report.diagnostics().stream().filter(Diagnostic::isError).toList();
                    repairs.add(summary(lastErrors));
                    messages.add(new LanguageModelPort.Message("user", PlannerPrompt.repair(lastErrors)));
                }
            }
        }
        var run = new Run(model.provider(), model.endpoint(), answeredBy, PlannerPrompt.VERSION, calls, in, out, cost, null);
        return new Outcome(Kind.failed, lastReport, null, null, null, null, null, lastErrors, repairs, null, description, run);
    }

    private static String summary(List<Diagnostic> errors) {
        return String.join("; ", errors.stream().map(d -> d.code() + " " + d.pointer()).toList());
    }

    /**
     * The JSON object in a reply. Models asked for bare JSON still sometimes wrap it in a code fence
     * or a sentence; take the outermost {...}. Anything else is passed through for the parser to reject.
     */
    public static String extractJson(String text) {
        String t = text.strip();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            int end = t.lastIndexOf("```");
            if (nl > 0 && end > nl) t = t.substring(nl + 1, end).strip();
        }
        int open = t.indexOf('{');
        int close = t.lastIndexOf('}');
        return open >= 0 && close > open ? t.substring(open, close + 1) : t;
    }
}
