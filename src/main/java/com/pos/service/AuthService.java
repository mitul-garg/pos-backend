package com.pos.service;

import java.util.Locale;
import java.util.Objects;

import com.pos.dao.AppUserDao;
import com.pos.dao.TenantDao;
import com.pos.exception.ForbiddenException;
import com.pos.exception.InvalidCredentialsException;
import com.pos.exception.TooManyRequestsException;
import com.pos.model.LoginData;
import com.pos.model.LoginForm;
import com.pos.model.SessionUserData;
import com.pos.pojo.AppUserPojo;
import com.pos.pojo.TenantPojo;
import com.pos.pojo.enums.TenantStatus;
import com.pos.util.JwtPrincipal;
import com.pos.util.JwtTokenService;
import com.pos.util.LoginAttemptGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Login, per-request session resolution, and the 401/403 split (C3). The backend half of
 * {@code frontend/src/services/authService.js}, whose 17 tests are the specification.
 *
 * <p><b>The ordering in {@link #login} is the security property.</b> Requirements.md
 * section 13.4 allows the 403s to name what went wrong — deactivated account, suspended
 * store — and that is only safe because they are unreachable until the password has been
 * proved. Hoist either status check above the password check and a specific 403 becomes
 * an oracle for "this tenant exists and this username is in it". Everything before the
 * password check therefore ends in one indistinguishable 401.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /** Verbatim from {@code authService.js}, so mock and backend read identically. */
    static final String DEACTIVATED_MESSAGE =
            "This account has been deactivated. Contact an administrator.";
    static final String SUSPENDED_MESSAGE =
            "This store has been suspended. Contact the platform administrator.";

    /**
     * C9 — a self-registered tenant's admin, before the verification link is
     * clicked. Verbatim from {@code authService.mock.js}. A distinct message from
     * {@link #SUSPENDED_MESSAGE} on purpose: both are the identical shape of 403
     * (correct credentials, tenant just isn't {@code ACTIVE}), but the fix for one
     * is "check your email" and the other is "contact the platform" — the message
     * has to say which.
     */
    static final String PENDING_VERIFICATION_MESSAGE =
            "Verify your email before signing in — check your inbox for the link.";

    /**
     * A real BCrypt hash of a value nothing can supply, used to spend the same time on an
     * unknown username that a known one costs. See {@link #login}.
     */
    private static final String ABSENT_USER_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private final TenantDao tenantDao;
    private final AppUserDao appUserDao;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokenService;
    private final LoginAttemptGuard loginAttemptGuard;

    /**
     * Constructor injection rather than {@code @Autowired} fields, which is what the rest
     * of the codebase uses.
     *
     * <p>The reason is concrete rather than stylistic. {@code WebConfig} component-scans
     * {@code com.pos.controller}, so any test booting the servlet context alone —
     * {@code HealthControllerTest}, {@code OpenApiGeneratorTest} — has to satisfy
     * {@code AuthController}'s dependency on this class. Field injection makes that
     * impossible to stub: Spring applies it to <i>every</i> bean, including one handed
     * back from a {@code @Bean} method, so the stub would drag in the DAOs, which drag in
     * an entity manager, which drags in a database. A constructor makes the object
     * constructible outside Spring, which is what {@code StubServiceConfig} needs.
     */
    @Autowired
    public AuthService(TenantDao tenantDao, AppUserDao appUserDao,
                       PasswordEncoder passwordEncoder, JwtTokenService jwtTokenService,
                       LoginAttemptGuard loginAttemptGuard) {
        this.tenantDao = tenantDao;
        this.appUserDao = appUserDao;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenService = jwtTokenService;
        this.loginAttemptGuard = loginAttemptGuard;
    }

    /**
     * {@code POST /api/auth/login}. Resolves the tenant by code, then the user by
     * {@code (tenantId, username)} — never by username alone, which is what lets two
     * stores each have an {@code admin}.
     *
     * <p>{@code readOnly}: logging in writes nothing but the in-memory {@link
     * LoginAttemptGuard} state, which isn't a database row.
     *
     * <p><b>Peer-review Phase 0:</b> checks {@link LoginAttemptGuard#isLocked} before
     * touching the database at all, keyed on the normalized {@code (tenantCode,
     * username)} pair regardless of whether it resolves to a real user — see that
     * class's Javadoc for why the symmetry matters. A wrong password records a failure
     * against the same key; a correct one clears it, right after the password check and
     * before {@link #requireUsable}, since proving the password is what this guard
     * cares about, not the account's status.
     */
    @Transactional(readOnly = true)
    public LoginData login(LoginForm form) {
        String code = normalize(form.getTenantCode());
        String username = normalize(form.getUsername());
        String password = form.getPassword() == null ? "" : form.getPassword();
        String accountKey = code + ":" + username;

        if (loginAttemptGuard.isLocked(accountKey)) {
            log.debug("Login blocked: '{}' is locked out after repeated failures", accountKey);
            throw new TooManyRequestsException();
        }

        // A suspended tenant still RESOLVES here. We are not allowed to admit it exists
        // until the password is proved, so its status is checked further down, not now.
        // A blank or unknown code resolves nothing and falls through to the 401 below;
        // it is NOT a platform login.
        TenantPojo tenant = TenantPojo.PLATFORM_CODE.equals(code)
                ? tenantDao.findPlatform()
                : tenantDao.findByCode(code);

        AppUserPojo user = tenant == null
                ? null
                : appUserDao.findByTenantAndUsername(tenant.getId(), username);

        if (user == null) {
            // Spend a BCrypt verification anyway. Without it an unknown tenant or
            // username answers in microseconds while a wrong password takes ~100ms, and
            // the uniform 401 leaks through response time what it refuses to say in its
            // message. The comparison is against a fixed hash and can never succeed.
            passwordEncoder.matches(password, ABSENT_USER_HASH);
            loginAttemptGuard.recordFailure(accountKey);
            log.debug("Login failed: no user for tenant code '{}'", code);
            throw new InvalidCredentialsException();
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            loginAttemptGuard.recordFailure(accountKey);
            log.debug("Login failed: wrong password for user {}", user.getId());
            throw new InvalidCredentialsException();
        }
        loginAttemptGuard.recordSuccess(accountKey);

        // --- past this line the caller has proved the password, so 403s may be specific
        requireUsable(user);

        String token = jwtTokenService.issue(user.getId(), tenantIdOf(user), user.getRole());
        log.info("Login: user {} ({}) in tenant {}", user.getId(), user.getRole(), code);
        return new LoginData(token, sessionUserOf(user));
    }

    /**
     * Turns a bearer token into the session, for {@code JwtAuthenticationFilter} to run
     * on <b>every authenticated request</b>.
     *
     * <p>The per-request database read is the point, not an oversight. It is what closes
     * the gap the frontend left open (backend-plan.md section 1, deferred obligation 5):
     * the mock re-checked tenant status only in {@code me()}, so a suspended store's open
     * tab kept transacting until someone refreshed. Here a token issued this morning
     * stops working the moment the row behind it changes.
     */
    @Transactional(readOnly = true)
    public SessionUserData resolveSession(String token) {
        JwtPrincipal principal = jwtTokenService.parse(token);

        AppUserPojo user = appUserDao.findWithTenant(principal.userId());
        if (user == null) {
            // Deleted rather than deactivated. Users are soft-deleted, so this is mostly
            // a token for a database that has since been recreated -- exactly what a
            // developer hits after `mvn test` drops and rebuilds pos_test.
            log.debug("Rejecting token: user {} no longer exists", principal.userId());
            throw new InvalidCredentialsException();
        }
        if (!Objects.equals(principal.tenantId(), tenantIdOf(user))) {
            // Unreachable without the signing key, since nothing moves a user between
            // tenants. Asserted anyway because the claim is what C4's filter will scope
            // queries by, and a claim that disagrees with its row must never be the one
            // that wins. Note the role claim gets no such treatment: a role CAN change,
            // and there the row is simply authoritative.
            log.warn("Rejecting token for user {}: tenant claim {} does not match row",
                    principal.userId(), principal.tenantId());
            throw new InvalidCredentialsException();
        }
        requireUsable(user);
        return sessionUserOf(user);
    }

    /**
     * The authenticated caller, for {@code GET /api/auth/me} and — from C6 — for
     * stamping {@code cashierId} / {@code processedBy}, which come from the session and
     * never from a request body.
     *
     * <p>Not transactional and hits no database: the filter already resolved this for
     * the current request.
     */
    public SessionUserData currentSession() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !(authentication.getPrincipal() instanceof SessionUserData session)) {
            // Only reachable if the security chain let an unauthenticated request past,
            // which would be a configuration bug rather than a caller's doing.
            throw new InvalidCredentialsException();
        }
        return session;
    }

    /**
     * The three 403s. All are specific, and all are safe only where this is called —
     * after the password, or behind a token that proves one was given.
     */
    private void requireUsable(AppUserPojo user) {
        if (!user.isActive()) {
            throw new ForbiddenException(DEACTIVATED_MESSAGE);
        }
        TenantPojo tenant = user.getTenant();
        // The reserved platform row has no lifecycle: it cannot be suspended (C8) or
        // self-registered (C9), and asking about its status would be asking about a
        // row that is infrastructure.
        if (tenant.isPlatform()) {
            return;
        }
        // C9: a self-registered tenant's admin, pre-verification. Checked before the
        // general not-ACTIVE branch below so it gets its own message rather than
        // falling into "suspended" -- the fix for one is "check your email", not
        // "contact the platform".
        if (tenant.getStatus() == TenantStatus.PENDING_VERIFICATION) {
            throw new ForbiddenException(PENDING_VERIFICATION_MESSAGE);
        }
        if (tenant.getStatus() != TenantStatus.ACTIVE) {
            throw new ForbiddenException(SUSPENDED_MESSAGE);
        }
    }

    /** Null for a platform user: the reserved row is storage, not a tenant on the wire. */
    private Long tenantIdOf(AppUserPojo user) {
        TenantPojo tenant = user.getTenant();
        return tenant.isPlatform() ? null : tenant.getId();
    }

    private SessionUserData sessionUserOf(AppUserPojo user) {
        TenantPojo tenant = user.getTenant();
        boolean platform = tenant.isPlatform();
        return new SessionUserData(
                user.getId(),
                platform ? null : tenant.getId(),
                user.getUsername(),
                user.getDisplayName(),
                user.getRole(),
                user.isActive(),
                platform ? null : tenant.getCode(),
                platform ? null : tenant.getName());
    }

    /**
     * Trimmed and lower-cased, matching the frontend. Tenant codes and usernames are
     * case-insensitive identifiers; a cashier typing {@code Admin} at 8am is not a
     * different account.
     */
    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
