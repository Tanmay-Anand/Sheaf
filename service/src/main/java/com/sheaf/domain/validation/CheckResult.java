package com.sheaf.domain.validation;

import com.sheaf.domain.common.Nullable;
import com.sheaf.domain.types.TableType;

import java.util.List;

/**
 * The outcome of type-checking a plan.
 *
 * @param output The final table type (query) or the target's table type after the edit; null
 *               when checking stopped early.
 * @param impact What an edit plan changes; null for query plans.
 */
public record CheckResult(
        @Nullable TableType output,
        List<Diagnostic> diagnostics,
        @Nullable ImpactReport impact
) {
    public CheckResult {
        diagnostics = List.copyOf(diagnostics);
    }

    public boolean valid() {
        return diagnostics.stream().noneMatch(Diagnostic::isError);
    }

    public List<Diagnostic> errors() {
        return diagnostics.stream().filter(Diagnostic::isError).toList();
    }

    public List<Diagnostic> warnings() {
        return diagnostics.stream().filter(d -> !d.isError()).toList();
    }
}
