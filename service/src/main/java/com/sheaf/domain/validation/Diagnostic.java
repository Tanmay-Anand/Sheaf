package com.sheaf.domain.validation;

import com.sheaf.domain.common.Nullable;

import java.util.Map;

/**
 * A structured, machine-readable finding. Doubles as the repair signal: {@code hint} is written
 * for the model, {@code message} for the person.
 *
 * @param pointer  RFC 6901 JSON Pointer into the plan, e.g. {@code /steps/2/measures/0/of}; "" for
 *                 the plan as a whole. Lets a repair target exactly the offending value.
 * @param step     0-based step (query) or operation (edit) index; null for plan-level findings.
 * @param operator The step's operator, e.g. {@code aggregate}, {@code dropColumn}, {@code sink}.
 * @param context  Structured detail: {@code received}, {@code expected}/{@code actual} types,
 *                 {@code candidates} for unknown names (closest first), dependents…
 */
public record Diagnostic(
        DiagnosticCode code,
        String pointer,
        @Nullable Integer step,
        @Nullable String operator,
        String message,
        String hint,
        Map<String, Object> context
) {
    public Diagnostic {
        context = Map.copyOf(context);
    }

    public boolean isError() {
        return code.isError();
    }
}
