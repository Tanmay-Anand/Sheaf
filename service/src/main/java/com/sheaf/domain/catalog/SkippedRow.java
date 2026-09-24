package com.sheaf.domain.catalog;

import com.sheaf.domain.common.Nullable;

/**
 * A row inside an entity's bounds that is not data.
 *
 * @param row   1-based sheet row.
 * @param label The row's text, e.g. {@code SUBTOTAL NORTH} or {@code North}.
 */
public record SkippedRow(int row, SkipReason reason, @Nullable String label) {}
