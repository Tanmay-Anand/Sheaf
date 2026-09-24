package com.sheaf.domain.ir;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The type a literal or parameter declares. A string literal is a date only when it says
 * {@code date} (or {@code datetime}); "2025-01-01" without it is text.
 */
public enum ValueType {
    STRING("string"),
    NUMBER("number"),
    BOOLEAN("boolean"),
    DATE("date"),
    DATETIME("datetime");

    private final String wire;

    ValueType(String wire) { this.wire = wire; }

    @JsonValue
    public String wire() { return wire; }
}
