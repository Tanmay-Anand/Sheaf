package com.sheaf.domain.catalog;

import com.sheaf.domain.common.Nullable;

import java.util.List;

/**
 * One column of an entity, as inferred by the profiler (and possibly corrected by the user).
 *
 * @param letter         Sheet column letter, e.g. {@code G}.
 * @param index          0-based position within the entity.
 * @param unit           Currency code for {@code currency} columns; absent when unconfirmed.
 * @param percentScale   1 when stored as a fraction (0.05), 100 when stored as a whole number (5).
 * @param keyCandidate   No nulls and every value distinct.
 * @param formula        Most cells are formulas.
 * @param numberFormat   Dominant non-General Excel number format.
 * @param dateFormats    Every date format seen, when the column holds dates.
 * @param exemplars      Up to five short sample values; absent when exemplars are switched off.
 * @param validationList Allowed values from an in-cell list validation rule.
 * @param dependents     Everything in the workbook that references this column.
 */
public record CatalogColumn(
        String name,
        String letter,
        int index,
        ScalarKind kind,
        @Nullable String unit,
        @Nullable Integer percentScale,
        boolean nullable,
        double nullRate,
        int distinctCount,
        boolean keyCandidate,
        boolean formula,
        @Nullable String numberFormat,
        @Nullable List<String> dateFormats,
        @Nullable List<String> exemplars,
        @Nullable List<String> validationList,
        List<ColumnDependent> dependents,
        List<String> warnings
) {}
