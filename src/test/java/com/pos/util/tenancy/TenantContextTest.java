package com.pos.util.tenancy;

import com.pos.exception.ForbiddenException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The holder's own contract, without a database or a servlet.
 *
 * <p>Small, but it pins the two decisions the rest of C4 is built on: that an absent
 * tenant resolves to a value no row can carry rather than to "unfiltered", and that
 * {@code null} means platform rather than "not yet set".
 */
@DisplayName("TenantContext")
class TenantContextTest {

    @AfterEach
    void clear() {
        // JUnit reuses the test thread, so a context left behind is inherited by the next
        // test -- the same failure mode as Jetty's pooled threads, in miniature.
        TenantContext.clear();
    }

    @Test
    @DisplayName("holds and releases a tenant")
    void holdsAndReleases() {
        assertFalse(TenantContext.isPresent(), "should start empty");

        TenantContext.set(7L);
        assertTrue(TenantContext.isPresent());
        assertEquals(7L, new TenantContext.Resolver().get());

        TenantContext.clear();
        assertFalse(TenantContext.isPresent());
    }

    @Test
    @DisplayName("treats a null tenant as no tenant, which is what a SUPER_ADMIN has")
    void nullIsNoTenant() {
        TenantContext.set(7L);

        // The filter reads this straight off SessionUserData.getTenantId(), which is null
        // for a platform user. If null were stored rather than cleared, isPresent() would
        // be true and requireTenant() would wave a SUPER_ADMIN through onto the POS
        // surface with no tenant to scope by.
        TenantContext.set(null);

        assertFalse(TenantContext.isPresent());
    }

    @Test
    @DisplayName("resolves an absent tenant to a value no row can carry, not to 'unfiltered'")
    void absentResolvesToNoTenant() {
        // The fail-closed choice, and the single most important line in the class. If the
        // filter went unparameterised here, a forgotten requireTenant() would return every
        // tenant's rows; instead it returns none. Ids are BIGINT AUTO_INCREMENT from 1.
        assertEquals(TenantContext.NO_TENANT, new TenantContext.Resolver().get());
        assertTrue(TenantContext.NO_TENANT < 0, "the sentinel must be unmatchable by a real id");
    }

    @Test
    @DisplayName("requireTenant admits a tenant user and refuses a tenant-less one")
    void requireTenantGuards() {
        TenantContext.set(7L);
        TenantContext.requireTenant();

        TenantContext.clear();
        ForbiddenException thrown =
                assertThrows(ForbiddenException.class, TenantContext::requireTenant);
        assertEquals(TenantContext.NO_TENANT_MESSAGE, thrown.getMessage());
    }

    @Test
    @DisplayName("keeps one thread's tenant off another's")
    void isPerThread() throws Exception {
        TenantContext.set(7L);

        Long[] seen = new Long[1];
        Thread other = new Thread(() -> seen[0] = new TenantContext.Resolver().get());
        other.start();
        other.join();

        // The property the whole design rests on. A new thread inherits nothing, which is
        // also why a POOLED thread must be cleared by hand -- it is not new.
        assertEquals(TenantContext.NO_TENANT, seen[0]);
        assertEquals(7L, new TenantContext.Resolver().get(), "this thread kept its own");
    }
}
