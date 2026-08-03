/**
 * JPA entities. These never leave the service layer: controllers speak
 * {@code com.pos.model} DTOs, so a lazy association cannot reach the serializer and
 * {@code app_user.password_hash} cannot reach the wire.
 *
 * <p>Populated in C2.
 */
package com.pos.pojo;
