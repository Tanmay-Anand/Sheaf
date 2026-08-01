package com.sheaf.domain.ir;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;

/**
 * Declares exactly where the evaluated result will be written.
 *
 * <p>The committer can only write to the sink declared in the validated plan —
 * not because the model behaves, but because the code has no other path.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "mode")
@JsonSubTypes({
        @JsonSubTypes.Type(value = Sink.NewSheetSink.class,       name = "newSheet"),
        @JsonSubTypes.Type(value = Sink.ExistingAnchorSink.class, name = "existingAnchor")
})
public sealed interface Sink permits Sink.NewSheetSink, Sink.ExistingAnchorSink {

    /**
     * Creates a new sheet. Cannot overwrite existing data by construction.
     *
     * @param name   Name of the new sheet to create.
     * @param anchor Top-left cell of the output range in A1 notation.
     */
    @JsonTypeName("newSheet")
    record NewSheetSink(String name, String anchor) implements Sink {}

    /**
     * Writes to a specific range in an existing sheet.
     * The preview must state the overwrite extent; commit is refused without confirmation.
     *
     * @param range       Target range in "Sheet!A1:D20" notation.
     * @param expectedCols Declared column count; validated against the final table type.
     */
    @JsonTypeName("existingAnchor")
    record ExistingAnchorSink(String range, int expectedCols) implements Sink {}
}
