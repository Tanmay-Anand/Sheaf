package com.sheaf.domain.catalog;

import com.fasterxml.jackson.annotation.JsonValue;

/** Physical column types. Mirrors the scalar types of the IR type system (ir-spec §5.1). */
public enum ScalarKind {
    NUMBER("number"),
    CURRENCY("currency"),
    PERCENT("percent"),
    DATE("date"),
    DATETIME("datetime"),
    STRING("string"),
    BOOLEAN("boolean"),
    CATEGORICAL("categorical"),
    /** No non-null values at all. */
    EMPTY("empty");

    private final String wire;

    ScalarKind(String wire) { this.wire = wire; }

    @JsonValue
    public String wire() { return wire; }
}
