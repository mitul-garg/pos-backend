package com.pos.model;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.fasterxml.jackson.annotation.JacksonAnnotationsInside;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

/**
 * Marks a DTO field holding a database id, so it serializes as a JSON <b>string</b>
 * rather than a number.
 *
 * <p>Ids are {@code BIGINT} in MySQL and {@code Long} in Java, but the frontend's mock
 * ids were strings ({@code "t1"}, {@code "p12"}) and {@code useParams} yields strings
 * regardless. Every {@code ===} comparison in the existing frontend therefore expects a
 * string, and {@code "12" === 12} is false in JavaScript. Sending numbers would break
 * those comparisons <b>silently</b> — no error, just a row that never matches.
 *
 * <p>Applied per field rather than by making the {@code ObjectMapper} stringify every
 * {@code Long}, because a blanket rule would also stringify counts — a paginated
 * envelope's {@code total} is a number the frontend does arithmetic on.
 *
 * <p>Deserialization needs nothing: Jackson already coerces a JSON string into a
 * {@code Long} on the way in, so a {@code Form} DTO accepts {@code "12"} as well.
 *
 * <p><b>Every id field in this package must carry it</b>, and
 * {@code JsonIdTest.everyIdFieldIsAnnotated} enforces that rather than leaving it to
 * review.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.FIELD, ElementType.METHOD })
// Makes this a true meta-annotation: Jackson unwraps the annotations on it as though
// they had been written directly on the field.
@JacksonAnnotationsInside
@JsonSerialize(using = ToStringSerializer.class)
public @interface JsonId {
}
