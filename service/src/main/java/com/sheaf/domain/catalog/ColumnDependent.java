package com.sheaf.domain.catalog;

/**
 * Something in the workbook that reads a column. Removing or renaming the column affects it.
 *
 * @param location Where the dependent lives, e.g. {@code Summary!B4} or {@code Sales!G5:G21},
 *                 or a name / chart / PivotTable identifier.
 * @param detail   The formula or source text, e.g. {@code =SUM(Sales!G:G)}.
 * @param refClass How the reference relates to this column, which decides what deleting it does.
 * @param fixedRows The reference names particular rows (C5, C5:C10) rather than the whole column or
 *                  the whole data range, so deleting rows can break it or silently shrink it.
 */
public record ColumnDependent(DependentKind kind, String location, String detail, DependentClass refClass, boolean fixedRows) {}
