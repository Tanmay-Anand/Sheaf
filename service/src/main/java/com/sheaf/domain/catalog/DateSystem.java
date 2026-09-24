package com.sheaf.domain.catalog;

import com.fasterxml.jackson.annotation.JsonValue;

/** Excel's date epoch: serial 1 is 1900-01-01 or 1904-01-02. */
public enum DateSystem {
    D1900("1900"),
    D1904("1904"),
    UNKNOWN("unknown");

    private final String wire;

    DateSystem(String wire) { this.wire = wire; }

    @JsonValue
    public String wire() { return wire; }
}
