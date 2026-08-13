package com.pos.service;

import java.util.List;
import java.util.Locale;

import com.pos.config.AppProperties;
import com.pos.dao.AppUserDao;
import com.pos.dao.TenantDao;
import com.pos.exception.NotFoundException;
import com.pos.exception.ValidationException;
import com.pos.model.UserData;
import com.pos.model.UserForm;
import com.pos.pojo.AppUserPojo;
import com.pos.pojo.enums.Role;
import com.pos.util.MaxLength;
import com.pos.util.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tenant-scoped user management (C8) — the port of {@code userService.js}, whose scope
 * is deliberately narrow: list, create, deactivate, and a merge-patch update that never
 * touches {@code username}. No arbitrary field-edit endpoint exists, matching
 * requirements.md section 5.7.
 *
 * <p><b>The one service that has to scope itself by hand.</b> Every other tenant-scoped
 * service (see {@code ProductService}) does nothing but call
 * {@link TenantContext#requireTenant()} as a guard and let the Hibernate filter do the
 * rest. {@link AppUserPojo} carries no {@code @Filter} — authentication has to read it
 * before there is a tenant to scope by — so this class both guards <i>and</i> filters,
 * passing {@code authService.currentSession().getTenantId()} into every
 * {@code AppUserDao} call the way a pre-C4 service would have had to. See
 * {@code prompts/c4-tenancy.md}'s note on {@code AppUserPojo} for why the exception exists
 * and why it is not a precedent for anything else.
 */
@Service
public class UserService {

    /** Matches the frontend's {@code 'User not found'} exactly. */
    static final String NOT_FOUND = "User not found";

    /** Verbatim from {@code userService.js}. */
    static final String USERNAME_REQUIRED = "Username is required";
    static final String PASSWORD_REQUIRED = "Password is required";
    static final String ROLE_INVALID = "Choose a valid role";
    static final String USERNAME_TAKEN = "Username is already taken";
    static final String LAST_ADMIN = "Cannot deactivate the last active admin";

    /**
     * Peer-review Phase 0 resource-creation guardrail: a durable ceiling on how many
     * users one tenant can hold, not a time-windowed rate limit — see {@link #create}.
     */
    static final String TOO_MANY_USERS = "This store has reached its user limit";

    private final AppUserDao appUserDao;
    private final TenantDao tenantDao;
    private final AuthService authService;
    private final PasswordEncoder passwordEncoder;
    private final AppProperties appProperties;

    /**
     * Constructor injection, for the reason recorded on {@code AuthService}: a servlet-
     * context-only test has to be able to stub this without dragging in a database.
     */
    @Autowired
    public UserService(AppUserDao appUserDao, TenantDao tenantDao, AuthService authService,
                       PasswordEncoder passwordEncoder, AppProperties appProperties) {
        this.appUserDao = appUserDao;
        this.tenantDao = tenantDao;
        this.authService = authService;
        this.passwordEncoder = passwordEncoder;
        this.appProperties = appProperties;
    }

    /**
     * {@code GET /api/users} — the caller's tenant only, sorted by username. A platform
     * {@code SUPER_ADMIN} never reaches this: {@code SecurityConfig} gates the whole path
     * on {@code ROLE_ADMIN}, and a {@code SUPER_ADMIN} holds no tenant role at all.
     */
    @Transactional(readOnly = true)
    public List<UserData> list() {
        TenantContext.requireTenant();
        Long tenantId = authService.currentSession().getTenantId();
        return appUserDao.findByTenant(tenantId).stream().map(this::toData).toList();
    }

    /**
     * {@code POST /api/users}.
     *
     * <p><b>{@code role} is checked against {@link Role#TENANT_ROLES}, not every role.</b>
     * A tenant {@code ADMIN} minting a {@code SUPER_ADMIN} would be privilege escalation
     * out of its own tenant — the exact hole that opened in the frontend when the role
     * was added to the shared list (BUGS.md, Phase 8/B4).
     *
     * <p>Reports every broken field in one 400 rather than the first, matching
     * {@code ProductService.validate}'s reasoning rather than the mock's sequential
     * throws — the response carries a field map either way, so there is no cost to
     * telling the caller everything wrong with the request at once.
     */
    @Transactional
    public UserData create(UserForm form) {
        TenantContext.requireTenant();
        Long tenantId = authService.currentSession().getTenantId();

        String username = trimToNull(form.getUsername());
        if (username == null) {
            throw ValidationException.field("username", USERNAME_REQUIRED);
        }
        if (form.getPassword() == null || form.getPassword().isEmpty()) {
            throw ValidationException.field("password", PASSWORD_REQUIRED);
        }
        if (form.getRole() == null || !Role.TENANT_ROLES.contains(form.getRole())) {
            throw ValidationException.field("role", ROLE_INVALID);
        }
        // Friendly half of uniqueness only -- uk_user_tenant_username is the enforcing
        // half, and AppUserDao.insert now flushes so a raced duplicate still lands here.
        if (appUserDao.findByTenantAndUsername(tenantId, username.toLowerCase(Locale.ROOT)) != null) {
            throw ValidationException.field("username", USERNAME_TAKEN);
        }
        // Peer-review Phase 1: length bounds the mock had no schema to answer to.
        // username/displayName match app_user's column widths; password's 72 is
        // BCrypt's own input ceiling (jBCrypt truncates/rejects past it depending on
        // version) -- bytes, approximated here as characters, a conservative match for
        // the overwhelmingly-ASCII case and never looser than the real limit for one
        // that isn't.
        MaxLength.require("username", "Username", username, 64);
        MaxLength.require("password", "Password", form.getPassword(), 72);
        MaxLength.require("displayName", "Display name", trimToNull(form.getDisplayName()), 120);
        // Peer-review Phase 0 resource-creation guardrail -- a durable ceiling on tenant
        // size, not tied to any one submitted field (the request is otherwise perfectly
        // valid), so this is a bare ValidationException like LAST_ADMIN below rather than
        // a field-level one.
        //
        // Two-tier as of Phase 1: the ACTIVE count is the real, reclaimable ceiling --
        // deactivating a user who left frees a slot again. The lifetime count (any
        // status, ever) is a much higher pure abuse backstop that never resets -- the
        // entire reason a durable ceiling was chosen here over a time-windowed one, so a
        // create/deactivate loop must still hit a wall eventually.
        if (appUserDao.countActiveByOwnTenant(tenantId) >= appProperties.getTenantMaxUsers()
                || appUserDao.countByOwnTenant(tenantId) >= appProperties.getTenantMaxUsersLifetime()) {
            throw new ValidationException(TOO_MANY_USERS);
        }

        AppUserPojo user = new AppUserPojo();
        user.setTenant(tenantDao.reference(tenantId));
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(form.getPassword()));
        String displayName = trimToNull(form.getDisplayName());
        user.setDisplayName(displayName == null ? username : displayName);
        user.setRole(form.getRole());
        // Never from the form, matching ProductService.create: a created user is always
        // active, so a caller asking for isActive:false would be creating a dead row.
        user.setActive(true);

        appUserDao.insert(user);
        return toData(user);
    }

    /**
     * {@code PUT /api/users/{id}} — a <b>merge patch</b> over {@code displayName},
     * {@code role}, {@code password} and {@code isActive}. {@code username} is on
     * {@link UserForm} but never read here, matching {@code userService.js}'s
     * {@code update}: an account's username is permanent once created.
     */
    @Transactional
    public UserData update(Long id, UserForm form) {
        TenantContext.requireTenant();
        Long tenantId = authService.currentSession().getTenantId();

        AppUserPojo user = appUserDao.findInTenant(id, tenantId);
        if (user == null) {
            throw new NotFoundException(NOT_FOUND);
        }

        if (form.getRole() != null) {
            if (!Role.TENANT_ROLES.contains(form.getRole())) {
                throw ValidationException.field("role", ROLE_INVALID);
            }
            user.setRole(form.getRole());
        }
        if (form.getDisplayName() != null) {
            MaxLength.require("displayName", "Display name", form.getDisplayName(), 120);
            user.setDisplayName(form.getDisplayName());
        }
        if (form.getPassword() != null && !form.getPassword().isEmpty()) {
            MaxLength.require("password", "Password", form.getPassword(), 72);
            user.setPasswordHash(passwordEncoder.encode(form.getPassword()));
        }
        if (form.getActive() != null) {
            user.setActive(form.getActive());
        }
        return toData(user);
    }

    /**
     * {@code DELETE /api/users/{id}} — a soft delete, guarded against locking a store out
     * of its own account.
     *
     * <p><b>Per tenant, matching {@code userService.js}'s identical guard</b>: another
     * store's admins can't keep this one out of its own account, so
     * {@link AppUserDao#countActiveAdmins} only ever counts the caller's own tenant. The
     * count runs <i>before</i> the row is deactivated, so the user about to be removed is
     * still counted among the active admins it is being compared against — exactly the
     * mock's {@code activeAdmins.length <= 1} check, not an off-by-one.
     *
     * <p><b>Locks the tenant row before anything else runs in this transaction</b> — see
     * {@link AppUserDao#lockTenant}'s Javadoc for why this count needs a lock the same
     * way a return's returnable-quantity check does, and why the lock has to come
     * <i>first</i>, not merely before the count. Found the hard way: an earlier version
     * called {@link AppUserDao#findInTenant} before the lock, and {@code LastAdminRaceIT}
     * still failed — two admins racing to deactivate each other both read "2 active
     * admins" and both succeeded. MySQL's REPEATABLE READ anchors every plain
     * (non-locking) read in a transaction to the snapshot taken at that transaction's
     * <i>first</i> read; {@code findInTenant} was that first read, so
     * {@link AppUserDao#countActiveAdmins}'s plain {@code SELECT} kept answering from a
     * snapshot taken before the lock wait even after the wait ended, never seeing the
     * other transaction's commit. {@code OrderDao.findForUpdate} being the first
     * statement in {@code ReturnService.create} is the same requirement, met by
     * construction there; this method needed it stated explicitly once it was violated.
     *
     * <p>This costs every deactivation a lock, not only an {@code ADMIN}'s — deliberately:
     * knowing whether the target is an {@code ADMIN} requires reading it, and any read
     * before the lock reintroduces the stale-snapshot bug. A low-throughput admin screen
     * is exactly where CONVENTIONS.md's "simple lock order beats need-for-speed" applies
     * (the same trade {@code TenantSequenceDao.next()} makes locking the whole tenant
     * rather than one sequence row).
     *
     * <p>Idempotent, matching {@code ProductService.deactivate}: deactivating an
     * already-inactive user is a no-op that answers 200. Returns the row, as the mock
     * does, so the client can update in place.
     */
    @Transactional
    public UserData deactivate(Long id) {
        TenantContext.requireTenant();
        Long tenantId = authService.currentSession().getTenantId();

        appUserDao.lockTenant(tenantId);
        AppUserPojo user = appUserDao.findInTenant(id, tenantId);
        if (user == null) {
            throw new NotFoundException(NOT_FOUND);
        }
        if (user.getRole() == Role.ADMIN && appUserDao.countActiveAdmins(tenantId) <= 1) {
            throw new ValidationException(LAST_ADMIN);
        }
        user.setActive(false);
        return toData(user);
    }

    /**
     * Mapped inside the transaction, so {@code user.getTenant()} is safe to read despite
     * being {@code LAZY} — only the id is read, which Hibernate answers from the proxy
     * without a second statement.
     */
    private UserData toData(AppUserPojo user) {
        return new UserData(
                user.getId(),
                user.getTenant().getId(),
                user.getUsername(),
                user.getDisplayName(),
                user.getRole(),
                user.isActive(),
                user.getCreatedAt());
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
