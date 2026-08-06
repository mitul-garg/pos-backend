package com.pos.util;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method that reaches across the tenant boundary on purpose — the platform
 * surface's only sanctioned way to do so (C8; CONVENTIONS.md "The multi-tenancy
 * adaptation this layout needs").
 *
 * <p>Two distinct reasons a method carries this:
 *
 * <ol>
 *   <li><b>It disables the Hibernate filter.</b> A {@code @Filter}ed entity (Product,
 *   PosOrder, ...) scopes to {@link TenantContext#NO_TENANT} for a caller with no
 *   tenant on the thread, which is every {@code SUPER_ADMIN} request — so reading
 *   another store's rows at all requires {@code session.disableFilter(FILTER_NAME)}
 *   first. {@code TenantDao.productCount}/{@code orderCount} are the only two call
 *   sites, and they exist to answer the row counts a suspension decision is made
 *   from.</li>
 *   <li><b>It reads or writes {@code Tenant}/{@code AppUser} rows outside the caller's
 *   own tenant.</b> Neither entity carries {@code @Filter} to disable in the first
 *   place — {@code Tenant} because it <i>is</i> the discriminator, {@code AppUser}
 *   because authentication has to read it before there is a tenant to scope by (see
 *   their own class Javadoc) — but the reach is exactly as cross-tenant in effect:
 *   {@code TenantService.list}/{@code get}/{@code create}/{@code updateStatus}, and
 *   {@code AppUserDao.countByTenant} when it is called once per store from that
 *   service rather than for the caller's own login.</li>
 * </ol>
 *
 * <p><b>The audit this buys.</b> {@code grep -r "@PlatformOperation" src/main} is meant
 * to be the *complete* list of code that can observe more than one tenant's data in one
 * request. A test enforces the narrower, mechanical half of that claim — every
 * {@code disableFilter} call site is inside a method carrying this annotation — because
 * that half is checkable by reflection; the wider claim, that nothing outside this list
 * reaches across the boundary, is a discipline the marker exists to make visible rather
 * than something a test can verify by itself.
 *
 * <p>Applied to methods, not classes: {@code TenantService} and {@code TenantDao} are
 * platform classes by naming convention alone, but nothing stops an ordinary
 * tenant-scoped method from living there, and a class-level annotation would say
 * "everything in here is cross-tenant" whether or not that stayed true.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface PlatformOperation {
}
