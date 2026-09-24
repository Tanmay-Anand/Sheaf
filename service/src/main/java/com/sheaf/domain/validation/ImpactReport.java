package com.sheaf.domain.validation;

import com.sheaf.domain.catalog.ColumnDependent;

import java.util.List;

/**
 * What an edit plan changes, computed statically before anything runs. Column names in
 * {@code removed}, {@code overwritten}, {@code moved} and {@code renamed.from} are the target's
 * original column names; {@code added} are the new names. Row counts come later, from evaluation.
 *
 * @param dependentsNeedingConsent Formulas and other readers that the edit would break or silently
 *                                 change. The commit needs the user's explicit choice for them.
 * @param untraceableReferences    The workbook has references Sheaf can't follow, so "nothing
 *                                 depends on this" can't be claimed with certainty.
 */
public record ImpactReport(
        List<String> added,
        List<String> removed,
        List<String> overwritten,
        List<Rename> renamed,
        List<String> moved,
        boolean removesRows,
        List<String> formulaColumnsOverwritten,
        List<AffectedDependent> dependentsNeedingConsent,
        boolean untraceableReferences
) {
    public ImpactReport {
        added = List.copyOf(added);
        removed = List.copyOf(removed);
        overwritten = List.copyOf(overwritten);
        renamed = List.copyOf(renamed);
        moved = List.copyOf(moved);
        formulaColumnsOverwritten = List.copyOf(formulaColumnsOverwritten);
        dependentsNeedingConsent = List.copyOf(dependentsNeedingConsent);
    }

    public record Rename(String from, String to) {}

    /** @param cause Why the dependent is affected: its column is removed, or rows it names are. */
    public record AffectedDependent(String column, ColumnDependent dependent, Cause cause) {}

    public enum Cause { columnRemoved, rowsRemoved }
}
