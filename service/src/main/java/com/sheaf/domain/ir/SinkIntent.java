package com.sheaf.domain.ir;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.sheaf.domain.common.Nullable;

/**
 * Where the model would like the result to go. Never a location: a new sheet (the binder picks a
 * free name), a place the user will choose, or an imported template by id.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "mode")
@JsonSubTypes({
        @JsonSubTypes.Type(value = SinkIntent.NewSheetIntent.class, name = "newSheet"),
        @JsonSubTypes.Type(value = SinkIntent.AnchorIntent.class,   name = "anchor"),
        @JsonSubTypes.Type(value = SinkIntent.TemplateIntent.class, name = "template")
})
public sealed interface SinkIntent permits SinkIntent.NewSheetIntent, SinkIntent.AnchorIntent, SinkIntent.TemplateIntent {

    /** @param name A suggested sheet name; the binder makes it valid and unique. */
    @JsonTypeName("newSheet")
    record NewSheetIntent(@Nullable String name) implements SinkIntent {}

    /** Write into an existing sheet at a place the user chooses at commit time. */
    @JsonTypeName("anchor")
    record AnchorIntent() implements SinkIntent {}

    @JsonTypeName("template")
    record TemplateIntent(String templateId) implements SinkIntent {}
}
