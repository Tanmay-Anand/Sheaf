package com.sheaf.domain.ir;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;

/** Where a column goes: always explicit (v1.1's absent {@code after} meant different things per operation). */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "at")
@JsonSubTypes({
        @JsonSubTypes.Type(value = Position.First.class, name = "first"),
        @JsonSubTypes.Type(value = Position.Last.class,  name = "last"),
        @JsonSubTypes.Type(value = Position.After.class, name = "after")
})
public sealed interface Position permits Position.First, Position.Last, Position.After {

    @JsonTypeName("first") record First() implements Position {}

    @JsonTypeName("last") record Last() implements Position {}

    @JsonTypeName("after") record After(String column) implements Position {}
}
