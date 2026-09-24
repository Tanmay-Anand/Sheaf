package com.sheaf.domain.catalog;

/**
 * A reference Sheaf cannot resolve statically. While any exist, "nothing depends on this
 * column" can never be claimed with certainty, and edit previews say so.
 */
public record UntraceableReference(UntraceableKind kind, String location, String detail) {}
