package com.sheaf.domain.catalog;

/**
 * What deleting a column does to a reference that reads it.
 *
 * <ul>
 *   <li>{@code exclusive}: the reference lies inside this one column (=C5, C2:C9); it becomes #REF!.
 *   <li>{@code spanning}: the reference spans other columns too (=SUM(A:D)); Excel shrinks it and
 *       its value changes <b>silently</b>. The most dangerous class.
 *   <li>{@code wholeColumn}: the reference is this entire sheet column (C:C); it becomes #REF!.
 *   <li>{@code wholeRow}: an entire-row reference (3:3); it loses a cell and its value can change.
 * </ul>
 */
public enum DependentClass { exclusive, spanning, wholeColumn, wholeRow }
