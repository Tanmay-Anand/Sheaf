package com.sheaf.domain.ir;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;

/**
 * Where a bound plan's result goes, as resolved by the binder.
 *
 * <p>The committer can only write to the sink declared in the validated plan — not because the
 * model behaves, but because the code has no other path. An anchor sink's location is chosen by the
 * user and arrives in the commit request; it is never part of the plan.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "mode")
@JsonSubTypes({
        @JsonSubTypes.Type(value = Sink.NewSheetSink.class, name = "newSheet"),
        @JsonSubTypes.Type(value = Sink.AnchorSink.class,   name = "anchor"),
        @JsonSubTypes.Type(value = Sink.TemplateSink.class, name = "template")
})
public sealed interface Sink permits Sink.NewSheetSink, Sink.AnchorSink, Sink.TemplateSink {

    /**
     * Creates a new sheet. Cannot overwrite existing data by construction.
     *
     * @param name   A valid sheet name not used by any existing sheet (case-insensitive).
     * @param anchor Top-left cell of the output, in A1 notation.
     */
    @JsonTypeName("newSheet")
    record NewSheetSink(String name, String anchor) implements Sink {}

    /**
     * Writes into an existing sheet at the location in the commit request. The preview states the
     * overwrite extent (from evaluation) and the commit needs the user's confirmation.
     */
    @JsonTypeName("anchor")
    record AnchorSink() implements Sink {}

    /**
     * Fills an imported template below its header. Output columns must equal the template's
     * writable header columns in name (case-insensitive) and order.
     */
    @JsonTypeName("template")
    record TemplateSink(String templateId, Integer headerRow, Integer firstDataRow) implements Sink {}
}
