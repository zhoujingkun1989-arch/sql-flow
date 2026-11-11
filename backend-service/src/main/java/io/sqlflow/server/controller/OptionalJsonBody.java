package io.sqlflow.server.controller;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binds an optional JSON request body ({@code Map<String, Object>} or {@code Object}) ONLY when
 * the request {@code Content-Type} is {@code application/json}, gracefully returning {@code null}
 * for {@code multipart/form-data}, {@code application/x-www-form-urlencoded}, or empty requests
 * without triggering {@code HttpMediaTypeNotSupportedException}.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface OptionalJsonBody {}
