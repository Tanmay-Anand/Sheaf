package com.sheaf.domain.validation;

import com.sheaf.domain.common.Nullable;
import com.sheaf.domain.ir.Plan;
import com.sheaf.domain.ir.PlanEnvelope;
import com.sheaf.domain.ir.PlanHasher;
import com.sheaf.domain.ir.UnboundPlan;
import com.sheaf.domain.types.TypeEnvironment;

import java.util.ArrayList;
import java.util.List;

/**
 * The type checker: a pure function {@code (Plan, TypeEnvironment) → CheckResult}. No I/O, no
 * framework, no model. Everything the planner produces passes through here before it can run.
 */
public final class TypeChecker {

    private TypeChecker() {}

    public static CheckResult check(Plan plan, TypeEnvironment env) {
        return switch (plan) {
            case Plan.QueryPlan q -> QueryChecker.check(q, env);
            case Plan.EditPlan e -> EditChecker.check(e, env);
        };
    }

    /**
     * The result of taking what the model wrote all the way to a checked, hashed plan.
     *
     * @param envelope The bound plan in its envelope; null when parsing or binding failed.
     * @param planHash Its hash; null when there is no envelope.
     * @param check    Parse, binding and type-check diagnostics together.
     */
    public record Checked(@Nullable PlanEnvelope envelope, @Nullable String planHash, CheckResult check) {
        public boolean valid() {
            return envelope != null && check.valid();
        }
    }

    /** Unbound plan → bind → check → envelope and hash. */
    public static Checked bindAndCheck(UnboundPlan unbound, TypeEnvironment env) {
        var bound = Binder.bind(unbound, env);
        if (bound.plan() == null) return new Checked(null, null, new CheckResult(null, bound.diagnostics(), null));
        var result = check(bound.plan(), env);
        List<Diagnostic> all = new ArrayList<>(bound.diagnostics());
        all.addAll(result.diagnostics());
        var envelope = PlanEnvelope.of(bound.plan());
        return new Checked(envelope, PlanHasher.hash(envelope), new CheckResult(result.output(), all, result.impact()));
    }

    /** Model output JSON (an unbound plan) → parse → bind → check. Parse failures come back as diagnostics. */
    public static Checked parseBindAndCheck(String unboundJson, TypeEnvironment env) {
        var parsed = PlanParser.parseUnbound(unboundJson);
        if (parsed.value() == null) return new Checked(null, null, new CheckResult(null, parsed.diagnostics(), null));
        return bindAndCheck(parsed.value(), env);
    }
}
