package com.sheaf.domain.common;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a record component that may be absent on the wire.
 *
 * <p>Everything else is required: the schema generator marks every component without this
 * annotation as {@code required}, which is what makes the generated TypeScript types strict.
 * Lives in the domain so the domain stays free of framework annotations.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT, ElementType.PARAMETER, ElementType.METHOD})
public @interface Nullable {}
