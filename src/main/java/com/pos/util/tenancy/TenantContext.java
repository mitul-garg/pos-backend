package com.pos.util.tenancy;

import java.util.function.Supplier;

import com.pos.exception.ForbiddenException;

/**
 * The tenant of the request currently being served, and the bridge that hands it to
 * Hibernate (C4). The backend's answer to the frontend's {@code mocks/session.js} — an
 * ambient tenant that nothing has to pass around.
 *
 * <p>Written once per request by {@code JwtAuthenticationFilter} and read by
 * {@link Resolver} whenever Hibernate builds a query against a {@code @Filter}ed entity.
 * <b>No controller, service or DAO reads it to scope anything.</b> The one deliberate
 * reader is {@link #requireTenant()}, and that is a guard rather than a parameter.
 *
 * <h2>Why a ThreadLocal</h2>
 * A servlet request is handled start to finish on one thread, so "the current thread"
 * and "the current request" are the same scope. That is what lets the tenant be read in
 * a DAO without appearing in a single method signature between here and there — and the
 * moment it <i>is</i> a parameter, it can be the wrong value. Spring Security's
 * {@code SecurityContextHolder} is the same device for the same reason.
 *
 * <p>It also has to be static: Hibernate instantiates {@link Resolver} itself, outside
 * the container, so a resolver holding an injected bean is not available to it.
 *
 * <h2>Clearing is the whole risk</h2>
 * Jetty pools threads. A request that does not clear this leaves its {@code tenantId}
 * for whatever request lands on that thread next, which then queries with <i>another
 * tenant's</i> id — a cross-tenant read rather than a stale value, and invisible to any
 * single-threaded test, because with one thread the leaked value is always your own.
 * Hence the {@code finally} in {@code JwtAuthenticationFilter} and the concurrency suite
 * (backend-plan.md section 10, risk 1).
 */
public final class TenantContext {

    /** The Hibernate filter defined on {@code com.pos.pojo}'s {@code package-info}. */
    public static final String FILTER_NAME = "tenantFilter";

    /** Its single parameter, supplied by {@link Resolver}. */
    public static final String PARAM_NAME = "tenantId";

    /**
     * The fragment every filtered entity gets appended to its {@code WHERE}. Written
     * against the <b>column</b> rather than a field because a filter condition is raw
     * SQL, not JPQL; the column is {@code tenant_id} on all seven filtered tables.
     */
    public static final String CONDITION = "tenant_id = :" + PARAM_NAME;

    /**
     * What {@link Resolver} supplies when there is no tenant on the thread — an
     * unauthenticated request, a platform {@code SUPER_ADMIN}, a startup task.
     *
     * <p><b>Fail-closed, and that is the point.</b> Leaving the filter unparameterised
     * in that case would mean a forgotten guard returns <i>every</i> tenant's rows;
     * supplying an id no row can carry means it returns none. Ids are
     * {@code BIGINT AUTO_INCREMENT} from 1, so a negative value can never match.
     */
    public static final Long NO_TENANT = -1L;

    /** Close enough to the frontend's {@code requireTenantId()} message to read alike. */
    public static final String NO_TENANT_MESSAGE = "This action requires a store account.";

    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    /**
     * Establishes the tenant for this thread. {@code null} is legitimate and means
     * "platform user" — not an error, and indistinguishable from never having been set,
     * which is correct: a {@code SUPER_ADMIN} has no tenant context at all.
     */
    public static void set(Long tenantId) {
        if (tenantId == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(tenantId);
        }
    }

    /** Always from a {@code finally}. See the class comment. */
    public static void clear() {
        CURRENT.remove();
    }

    public static boolean isPresent() {
        return CURRENT.get() != null;
    }

    /**
     * The guard on a tenant-scoped operation — the only place outside this class that
     * asks about the tenant at all.
     *
     * <p>Returns nothing on purpose. A method handing back the {@code tenantId} would
     * eventually have it passed into a service or a DAO, which is the thing this whole
     * design exists to make impossible.
     *
     * <p>A 403 rather than an empty list: requirements.md section 13.2 says the POS
     * surface is <b>unavailable</b> to a {@code SUPER_ADMIN}, not merely empty, and
     * "this store has no products" is a far worse thing to show an operator than "you
     * are on the wrong surface". This is not an isolation decision and the
     * 404-never-403 rule does not apply — no tenant's data is being concealed here, so
     * the answer reveals nothing.
     */
    public static void requireTenant() {
        if (!isPresent()) {
            throw new ForbiddenException(NO_TENANT_MESSAGE);
        }
    }

    /**
     * Supplies {@code tenantFilter}'s parameter.
     *
     * <p>Instantiated by Hibernate rather than by Spring — hence public, static and
     * no-arg — and consulted <b>per query</b> rather than once per session. That timing
     * is what lets one pooled thread, and one Hibernate session, serve two tenants in
     * succession.
     */
    public static final class Resolver implements Supplier<Long> {

        @Override
        public Long get() {
            Long tenantId = CURRENT.get();
            return tenantId == null ? NO_TENANT : tenantId;
        }
    }
}
