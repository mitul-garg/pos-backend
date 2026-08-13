package com.pos.pojo;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import com.pos.util.TenantContext;
import jakarta.persistence.Entity;
import org.hibernate.annotations.Filter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.RegexPatternTypeFilter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>The test that makes C5–C8 unable to forget.</b> Every entity that belongs to a
 * tenant must carry {@code @Filter}, and this asserts it by scanning rather than by
 * trusting review.
 *
 * <p>The failure it guards against has no symptom. A new entity without the annotation
 * compiles, persists, queries and returns rows perfectly — it just returns <i>everyone's</i>
 * rows, and only a test that thought to look for another tenant's data would ever notice.
 * C6 and C7 each add three such entities to a codebase where the surrounding ones are all
 * filtered, which is exactly the situation where an omission looks like the norm.
 *
 * <p>Needs no database: this is a statement about annotations, and the whole point is that
 * it fails at {@code mvn test} on a laptop rather than at a security review.
 */
@DisplayName("the tenant filter's coverage of com.pos.pojo")
class TenantFilterCoverageTest {

    /**
     * The one entity that carries {@code tenant_id} and deliberately is not filtered.
     *
     * <p>Named here rather than skipped silently, and asserted from both sides below —
     * {@link #appUserIsUnfilteredOnPurpose()} fails if someone "fixes" it by adding the
     * annotation, which would break every login in the application.
     */
    private static final Class<?> UNFILTERED_BY_DESIGN = AppUserPojo.class;

    /**
     * Seven filtered entities as of C4: ProductPojo, VariantPojo, PosOrderPojo, OrderLinePojo,
     * SalesReturnPojo, ReturnLinePojo, TenantSequencePojo.
     *
     * <p>Pinned so that <b>deleting an entity</b> is as loud as forgetting to annotate a
     * new one — without it, a scan that silently matched less would still pass. Raise it
     * in the same change that adds an entity.
     */
    private static final int EXPECTED_FILTERED_ENTITIES = 7;

    @Test
    @DisplayName("is complete — every entity owned by a tenant is filtered")
    void everyTenantOwnedEntityIsFiltered() {
        List<String> unfiltered = new ArrayList<>();

        for (Class<?> entity : tenantOwnedEntities()) {
            if (entity.equals(UNFILTERED_BY_DESIGN)) {
                continue;
            }
            if (entity.getAnnotation(Filter.class) == null) {
                unfiltered.add(entity.getSimpleName());
            }
        }

        assertTrue(unfiltered.isEmpty(),
                () -> "these entities carry a tenant but no @Filter, so every query "
                        + "against them returns EVERY tenant's rows: " + unfiltered);
    }

    @Test
    @DisplayName("names the right filter and condition, not just some filter")
    void filtersUseTheOneDefinition() {
        for (Class<?> entity : tenantOwnedEntities()) {
            Filter filter = entity.getAnnotation(Filter.class);
            if (filter == null) {
                continue;
            }
            // A @Filter naming a definition that does not exist is not a startup error --
            // Hibernate simply never applies it. Same silent outcome as omitting it.
            assertEquals(TenantContext.FILTER_NAME, filter.name(),
                    () -> entity.getSimpleName() + " names an unknown filter");
            assertEquals(TenantContext.CONDITION, filter.condition(),
                    () -> entity.getSimpleName() + " scopes on something other than tenant_id");
        }
    }

    @Test
    @DisplayName("covers exactly the entities it did when this was written")
    void coversTheExpectedNumberOfEntities() {
        long filtered = tenantOwnedEntities().stream()
                .filter(entity -> entity.getAnnotation(Filter.class) != null)
                .count();

        assertEquals(EXPECTED_FILTERED_ENTITIES, filtered,
                "the set of filtered entities changed -- if that was deliberate, update "
                        + "EXPECTED_FILTERED_ENTITIES and prompts/c4-tenancy.md together");
    }

    @Test
    @DisplayName("leaves AppUser unfiltered on purpose, and would break login otherwise")
    void appUserIsUnfilteredOnPurpose() {
        // The inverse assertion, so the exception cannot be quietly removed. Filtered,
        // AppUserDao.findByTenantAndUsername would run against NO_TENANT -- authentication
        // happens BEFORE there is a tenant, so it cannot be scoped by its own answer --
        // and every login in the application would return the generic 401.
        assertNull(UNFILTERED_BY_DESIGN.getAnnotation(Filter.class),
                "AppUser must stay unfiltered; see the note on the class and c4-tenancy.md");
    }

    /** Every {@code @Entity} in {@code com.pos.pojo} with a {@link TenantPojo} association. */
    private List<Class<?>> tenantOwnedEntities() {
        List<Class<?>> owned = new ArrayList<>();

        for (Class<?> candidate : entityClasses()) {
            for (Field field : candidate.getDeclaredFields()) {
                if (TenantPojo.class.equals(field.getType())) {
                    owned.add(candidate);
                    break;
                }
            }
        }

        // A scan matching nothing would make all of the above pass forever, which is the
        // classic way a guard like this rots into decoration.
        assertFalse(owned.isEmpty(), "the com.pos.pojo scan found no tenant-owned entities");
        return owned;
    }

    private List<Class<?>> entityClasses() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new RegexPatternTypeFilter(Pattern.compile(".*")));

        List<Class<?>> classes = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents("com.pos.pojo")) {
            try {
                Class<?> type = Class.forName(definition.getBeanClassName());
                if (type.isAnnotationPresent(Entity.class)) {
                    classes.add(type);
                }
            } catch (ClassNotFoundException ex) {
                throw new IllegalStateException("scanned a class that will not load", ex);
            }
        }
        return classes;
    }
}
