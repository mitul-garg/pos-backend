package com.pos.pojo;

import com.pos.pojo.enums.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A person who can log in. {@code user} is close enough to reserved across databases
 * that the table is {@code app_user}.
 *
 * <p>Username is unique <b>per tenant</b>: two stores can each have an {@code admin},
 * and the seed data relies on it. The tenant code supplied at login is what
 * disambiguates them.
 *
 * <p><b>The one entity with a {@code tenant_id} that carries no {@code @Filter}</b>, and
 * the omission is deliberate rather than missed (C4). Authentication is what establishes
 * which tenant a caller is in, so it runs <i>before</i> there is a tenant to scope by —
 * filtered, {@code AppUserDao.findByTenantAndUsername} would be evaluated against
 * {@link com.pos.util.TenantContext#NO_TENANT} and every login on earth would fail.
 * {@code AppUserDao} therefore takes the tenant explicitly, which is safe only because
 * it is reached from exactly one place. C8's user management has to re-establish the
 * scoping this forgoes; see {@code prompts/c4-tenancy.md}.
 */
@Entity
@Table(
        name = "app_user",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_user_tenant_username",
                columnNames = { "tenant_id", "username" }
        ),
        indexes = {
                @Index(name = "idx_user_tenant", columnList = "tenant_id"),
                @Index(name = "idx_user_email", columnList = "email")
        }
)
public class AppUserPojo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /**
     * Never null — platform users point at the reserved {@code platform} tenant rather
     * than carrying a NULL. See {@link TenantPojo#isPlatform()} for why that matters.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "tenant_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_app_user_tenant")
    )
    private TenantPojo tenant;

    @Column(name = "username", nullable = false, length = 64)
    private String username;

    /**
     * BCrypt (60 characters; the column has headroom for a cost or algorithm change).
     *
     * <p><b>This must never leave the service layer.</b> The frontend mock stores
     * plaintext and that does not carry over — and the reason controllers return DTOs
     * rather than entities is largely this field: every "we accidentally returned the
     * password hash" incident starts with serializing an entity.
     */
    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "display_name", nullable = false, length = 120)
    private String displayName;

    /**
     * Never used for login — username/password is unchanged (C9,
     * `tenant-registration-plan.md` §3). Nullable: every existing seeded/platform-created
     * user has none, and it's required going forward only for a self-registered tenant's
     * first admin, as the address {@code TenantRegistrationService} sends the
     * verification/resend email to. Not unique — a global unique constraint here would
     * be the same mistake as a global one on {@code username}, and per-tenant uniqueness
     * would not stop the abuse the guardrail below exists for.
     *
     * <p><b>Indexed, and looked up by it, as of peer-review Phase 0's resource-creation
     * guardrail</b> — {@code AppUserDao.countByEmail}, a lifetime cap on how many tenants
     * one admin email can register (see its Javadoc). Before that guardrail, this field
     * really was never looked up by, globally or per tenant, which is why the index did
     * not exist until now.
     */
    @Column(name = "email", length = 254)
    private String email;

    // VARCHAR, not MySQL's native ENUM: adding a value to a MySQL ENUM is a table
    // alter, and hbm2ddl.auto=update will not perform it. Hibernate 6 defaults to the
    // native type on MySQL, so this has to be said explicitly on every enum field.
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "role", nullable = false, length = 16)
    private Role role;

    /** Soft delete. Users are deactivated, never removed — orders reference them. */
    @Column(name = "is_active", nullable = false)
    @ColumnDefault("true")
    private boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public TenantPojo getTenant() {
        return tenant;
    }

    public void setTenant(TenantPojo tenant) {
        this.tenant = tenant;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
