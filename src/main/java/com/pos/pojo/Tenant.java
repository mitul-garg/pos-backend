package com.pos.pojo;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A tenant is a single store — the flat model in requirements.md section 13.1, with no
 * organisation-to-stores hierarchy.
 *
 * <p><b>Read this as the boundary, not just another table.</b> Every other entity here
 * lives inside one tenant and nothing crosses; from C4 a Hibernate filter appends
 * {@code tenant_id = :tenantId} to every query, so a forgotten {@code WHERE} cannot leak.
 *
 * <p>{@code code} is the one globally-unique field in the whole model, because it is the
 * login discriminator: usernames, SKUs, QR codes and order numbers are all unique only
 * <i>within</i> a tenant.
 */
@Entity
@Table(
        name = "tenant",
        uniqueConstraints = @UniqueConstraint(name = "uk_tenant_code", columnNames = "code")
)
public class Tenant {

    /**
     * The reserved code a platform {@code SUPER_ADMIN} logs in with, mirroring the
     * frontend's {@code PLATFORM_TENANT_CODE}.
     *
     * <p>Chosen over "leave the field blank" so the value is explicit at every layer —
     * form, request body, server log — instead of an unreadable empty string, and so a
     * tenant user who forgets their code gets a plain failed login rather than silently
     * landing in the platform namespace (requirements.md section 13.4).
     *
     * <p>C8 must reject it, along with {@code admin}/{@code super}/{@code system}, when
     * a tenant is created. Until then nothing enforces that no tenant claims it — which
     * is why {@code TenantDao.findPlatform()} matches on {@code is_platform} rather than
     * on this string.
     */
    public static final String PLATFORM_CODE = "platform";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    /** e.g. {@code mg-road}. Globally unique — see the class comment. */
    @Column(name = "code", nullable = false, length = 64)
    private String code;

    // VARCHAR, not MySQL's native ENUM: adding a value to a MySQL ENUM is a table
    // alter, and hbm2ddl.auto=update will not perform it. Hibernate 6 defaults to the
    // native type on MySQL, so this has to be said explicitly on every enum field.
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 16)
    private TenantStatus status = TenantStatus.ACTIVE;

    /**
     * True for exactly one reserved row, the {@code platform} tenant that owns the
     * {@code SUPER_ADMIN} users.
     *
     * <p>That row exists for a concrete reason: <b>MySQL treats NULLs as distinct in a
     * unique index</b>, so a nullable {@code tenant_id} would leave
     * {@code uk_user_tenant_username} placing no constraint at all on platform users —
     * ten {@code superadmin} rows would all be accepted. The alternative fix is a
     * generated column, which Hibernate cannot emit from annotations.
     *
     * <p>It is a <b>storage detail, not a wire contract</b>: the API still reports
     * {@code tenantId: null} for platform users. The row is excluded from
     * {@code GET /api/tenants} and cannot be suspended (C8).
     */
    @Column(name = "is_platform", nullable = false)
    @ColumnDefault("false")
    private boolean platform = false;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public TenantStatus getStatus() {
        return status;
    }

    public void setStatus(TenantStatus status) {
        this.status = status;
    }

    public boolean isPlatform() {
        return platform;
    }

    public void setPlatform(boolean platform) {
        this.platform = platform;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
