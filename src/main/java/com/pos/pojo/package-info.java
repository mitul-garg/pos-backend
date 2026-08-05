/**
 * JPA entities. These never leave the service layer: controllers speak
 * {@code com.pos.model} DTOs, so a lazy association cannot reach the serializer and
 * {@code app_user.password_hash} cannot reach the wire.
 *
 * <p>Populated in C2. C4 declared the tenant filter below and applied it, as
 * {@code @Filter}, to every entity that carries a {@code tenant_id} — all of them
 * except {@link com.pos.pojo.AppUser}, which authentication has to read before there
 * is a tenant to scope by.
 */
@FilterDef(
        name = TenantContext.FILTER_NAME,

        // Enabled on every Hibernate session automatically. The alternative is a
        // session.enableFilter(...) somewhere per request, which is one more thing to
        // forget -- and "the query that forgot" is the exact failure this mechanism
        // exists to make impossible. Nothing switches it on, so nothing can fail to.
        autoEnabled = true,

        // Closes the classic trap with Hibernate filters: by default they apply to
        // queries but NOT to em.find(). Without this a DAO doing find(Product.class, id)
        // returns another tenant's row quite happily -- which is precisely the case C4
        // is "done when" it prevents. C8 will disable the filter by name for the
        // platform surface; nothing else may.
        applyToLoadByKey = true,

        parameters = @ParamDef(
                name = TenantContext.PARAM_NAME,
                type = Long.class,
                // Hibernate consults this per query, so the value is the tenant of the
                // request in flight rather than whatever it was when the session opened.
                // TenantContext explains why that timing is load-bearing.
                resolver = TenantContext.Resolver.class))
package com.pos.pojo;

import com.pos.util.TenantContext;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;
