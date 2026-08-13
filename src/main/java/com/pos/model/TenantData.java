package com.pos.model;

import java.time.Instant;

import com.pos.pojo.enums.TenantStatus;

/**
 * A tenant on the wire, the row counts included (C8) — mirrors the frontend's
 * {@code withCounts}, which folds {@code userCount}/{@code productCount}/
 * {@code orderCount} onto every tenant a {@code SUPER_ADMIN} sees so a suspension is a
 * judged decision ("0 users, 0 orders" vs "4 users, 300 orders") rather than a guess.
 *
 * <p>The three counts are plain {@code long}s, not {@link JsonId} strings, for the same
 * reason {@link PageData#getTotal()} is: they are numbers a client might do arithmetic
 * or a comparison on, and none of them ends in {@code Id} — {@code JsonIdTest} only ever
 * looks for that suffix.
 *
 * <p>No {@code adminUsername} or similar — this is what {@code GET /api/tenants} and
 * {@code GET /api/tenants/{id}} return, not what {@link TenantForm} sent in. The
 * platform's reserved row is never represented by this class at all: it is excluded
 * from every list and a direct {@code GET}/{@code PATCH} by its id answers 404, exactly
 * as {@code TenantPojo.isPlatform()}'s Javadoc says it must.
 */
public class TenantData {

    @JsonId
    private final Long id;

    private final String name;
    private final String code;
    private final TenantStatus status;
    private final Instant createdAt;
    private final long userCount;
    private final long productCount;
    private final long orderCount;

    public TenantData(Long id, String name, String code, TenantStatus status, Instant createdAt,
                      long userCount, long productCount, long orderCount) {
        this.id = id;
        this.name = name;
        this.code = code;
        this.status = status;
        this.createdAt = createdAt;
        this.userCount = userCount;
        this.productCount = productCount;
        this.orderCount = orderCount;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getCode() {
        return code;
    }

    public TenantStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public long getUserCount() {
        return userCount;
    }

    public long getProductCount() {
        return productCount;
    }

    public long getOrderCount() {
        return orderCount;
    }
}
