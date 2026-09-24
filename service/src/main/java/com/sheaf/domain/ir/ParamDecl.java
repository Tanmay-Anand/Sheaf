package com.sheaf.domain.ir;

/**
 * A run-time parameter, e.g. {@code asOf} for "last 8 weeks". Plans reference it with a
 * {@code param} expression; the value arrives in the commit request and is recorded in the run log,
 * so a saved recipe replays with fresh values instead of a baked-in date.
 */
public record ParamDecl(String name, ValueType valueType) {}
