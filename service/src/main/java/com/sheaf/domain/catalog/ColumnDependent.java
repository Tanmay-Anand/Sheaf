package com.sheaf.domain.catalog;

/**
 * Something in the workbook that reads a column. Removing or renaming the column affects it.
 *
 * @param location Where the dependent lives, e.g. {@code Summary!B4} or {@code Sales!G5:G21},
 *                 or a name / chart / PivotTable identifier.
 * @param detail   The formula or source text, e.g. {@code =SUM(Sales!G:G)}.
 */
public record ColumnDependent(DependentKind kind, String location, String detail) {}
