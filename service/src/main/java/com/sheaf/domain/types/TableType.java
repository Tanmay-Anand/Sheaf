package com.sheaf.domain.types;

import com.sheaf.domain.common.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * An ordered record of named column types (ir-spec §5.2). Order matters: sinks write columns in
 * this order and the template check compares it.
 *
 * <p>Names are matched <b>case-insensitively</b>, as Excel does: its {@code =} ignores case and an
 * Excel Table won't hold "Name" and "name" side by side (it silently renames one to "Name2").
 *
 * @param dynamicValue After a pivot, the type of the data-dependent value columns; null otherwise.
 *                     The one acknowledged static-typing exception in the algebra.
 */
public record TableType(List<ColumnType> columns, @Nullable ColumnType dynamicValue) {

    public TableType {
        columns = List.copyOf(columns);
    }

    public static TableType of(List<ColumnType> columns) {
        return new TableType(columns, null);
    }

    /** The case-insensitive identity of a column name. */
    public static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    public Optional<ColumnType> find(String name) {
        String k = key(name);
        return columns.stream().filter(c -> key(c.name()).equals(k)).findFirst();
    }

    public boolean has(String name) {
        return find(name).isPresent();
    }

    public int indexOf(String name) {
        String k = key(name);
        for (int i = 0; i < columns.size(); i++) if (key(columns.get(i).name()).equals(k)) return i;
        return -1;
    }

    public List<String> names() {
        return columns.stream().map(ColumnType::name).toList();
    }

    public boolean dynamic() {
        return dynamicValue != null;
    }

    public TableType plus(ColumnType c) {
        var next = new ArrayList<>(columns);
        next.add(c);
        return new TableType(next, dynamicValue);
    }

    /** Rendered one column per entry, plus "*: T? (dynamic)" after a pivot. Used by golden tests. */
    public List<String> render() {
        var out = new ArrayList<>(columns.stream().map(ColumnType::render).toList());
        if (dynamicValue != null) out.add("*: " + dynamicValue.type().render() + "? (dynamic)");
        return out;
    }
}
