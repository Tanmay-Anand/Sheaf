package com.sheaf.domain.catalog;

import com.sheaf.domain.common.Nullable;

import java.util.List;

/**
 * A rectangular block of tabular data: an Excel Table, or a region the profiler detected.
 *
 * @param name          Stable, workbook-unique name (table name, or the sheet name for a region).
 * @param address       Full address including the header row, e.g. {@code Sales!A3:I23}.
 * @param headerRow     1-based sheet row of the (last) header row.
 * @param headerRows    Number of header rows; 2 when a merged group row sits above the labels.
 * @param firstDataRow  1-based sheet row of the first row after the header.
 * @param lastDataRow   1-based sheet row of the last row belonging to the entity.
 * @param dataRowCount  Rows profiled as data (skipped rows excluded).
 * @param skippedRows   Rows inside the entity that are not data: blanks, section labels, totals.
 * @param contentHash   Hash of the entity's cell contents; a change means the entity is stale.
 * @param tableName     The Excel Table name when {@code kind} is {@code table}.
 */
public record CatalogEntity(
        String name,
        EntityKind kind,
        String sheet,
        String address,
        int headerRow,
        int headerRows,
        int firstDataRow,
        int lastDataRow,
        int dataRowCount,
        List<SkippedRow> skippedRows,
        List<CatalogColumn> columns,
        String contentHash,
        @Nullable String tableName
) {}
