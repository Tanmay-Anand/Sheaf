package com.sheaf.application;

import com.sheaf.domain.common.Nullable;
import com.sheaf.domain.ir.PlanEnvelope;
import com.sheaf.domain.types.ColumnType;
import com.sheaf.domain.types.TableType;
import com.sheaf.domain.validation.Diagnostic;
import com.sheaf.domain.validation.ImpactReport;
import com.sheaf.domain.validation.TypeChecker;

import java.util.List;

/**
 * What the pane needs after a plan is checked: the bound plan and its hash (to evaluate, preview
 * and commit), the output's column types (to format what is written), and every diagnostic.
 *
 * @param envelope     The bound plan; null when parsing or binding failed.
 * @param planHash     The envelope's hash; null without an envelope.
 * @param output       The output table's columns in order; empty when checking stopped early.
 * @param dynamicValue For a pivot: the type of every column whose name is only known at run time.
 * @param impact       What an edit plan changes; null for query plans.
 */
public record CheckReport(
        boolean valid,
        @Nullable PlanEnvelope envelope,
        @Nullable String planHash,
        List<OutputColumn> output,
        @Nullable OutputColumn dynamicValue,
        List<Diagnostic> diagnostics,
        @Nullable ImpactReport impact
) {
    /** @param type As rendered by the type checker: "number", "currency:INR", "percent", "date", "categorical"… */
    public record OutputColumn(String name, String type, boolean nullable) {
        static OutputColumn of(ColumnType c) {
            return new OutputColumn(c.name(), c.type().render(), c.nullable());
        }
    }

    public static CheckReport of(TypeChecker.Checked checked) {
        TableType out = checked.check().output();
        return new CheckReport(
                checked.valid(),
                checked.envelope(),
                checked.planHash(),
                out == null ? List.of() : out.columns().stream().map(OutputColumn::of).toList(),
                out == null || out.dynamicValue() == null ? null : OutputColumn.of(out.dynamicValue()),
                checked.check().diagnostics(),
                checked.check().impact());
    }
}
