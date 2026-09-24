package com.sheaf.domain.catalog;

import com.sheaf.domain.common.Nullable;

/**
 * A user's answer to "is this right?". Corrections survive rescans and win over inference.
 * Either a column type correction ({@code column} with {@code kind} and optionally {@code unit})
 * or a header-row correction for an entity ({@code headerRow}).
 */
public record CatalogCorrection(
        String sheet,
        String entity,
        @Nullable String column,
        @Nullable ScalarKind kind,
        @Nullable String unit,
        @Nullable Integer headerRow
) {}
