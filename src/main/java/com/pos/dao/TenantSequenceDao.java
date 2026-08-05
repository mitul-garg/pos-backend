package com.pos.dao;

import com.pos.pojo.SequenceKind;
import com.pos.pojo.Tenant;
import com.pos.pojo.TenantSequence;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;

/**
 * Hands out the next value in a tenant's own counter (C5) — the machinery that replaces
 * the {@code AUTO_INCREMENT} going multi-tenant cost us.
 *
 * <p><b>Read this before using it in C6 or C7.</b> QR codes are the easy customer; order
 * and return numbers are the ones where getting it wrong produces a duplicate invoice
 * number, and the rule is the same for all three:
 *
 * <blockquote><b>Call this in the same transaction as the insert it numbers.</b> The value
 * is reserved by a row lock held until that transaction commits, so a second caller waits
 * rather than reading the same number. Take the value in one transaction and insert in
 * another and the lock is released in between, which is the race this class exists to
 * prevent, dressed up to look safe.</blockquote>
 *
 * <p>{@code MAX(number) + 1} would not do, and neither would a plain read-then-write.
 * MySQL's default REPEATABLE READ does not help: a plain {@code SELECT} is a non-locking
 * snapshot read, so two transactions both seeing the same value is defined behaviour
 * rather than misconfiguration. Hence the pessimistic lock below.
 */
@Repository
public class TenantSequenceDao {

    @PersistenceContext
    private EntityManager em;

    /**
     * The next value for this tenant's counter of the given kind, starting at 1.
     *
     * <p><b>Note the query names no tenant.</b> The {@code tenantFilter} on
     * {@link TenantSequence} scopes it, exactly as it scopes every other query — so this
     * can only ever lock and advance the caller's own counter, whatever the {@code tenant}
     * argument says. That argument is used for one thing: constructing the row the first
     * time a tenant asks, where there is nothing yet to point the foreign key at. Its
     * caller takes it from the parent entity of the row being numbered, which was itself
     * loaded through a filtered read.
     *
     * <p><b>JPQL rather than {@code em.find(..., PESSIMISTIC_WRITE)}</b>, and not for
     * style: {@code find} would need the composite key, which means naming the tenant id,
     * which is the thing no signature here may do.
     *
     * <p>The first call for a tenant inserts the row, and two simultaneous first calls
     * cannot both win. InnoDB takes a gap lock for a {@code SELECT ... FOR UPDATE} that
     * matches nothing, so the second caller blocks; and if it somehow does not, the
     * primary key {@code (tenant_id, kind)} refuses the duplicate. The {@code flush} is
     * what makes that refusal happen here rather than at commit.
     */
    public long next(SequenceKind kind, Tenant tenant) {
        TenantSequence sequence = em.createQuery(
                        "SELECT s FROM TenantSequence s WHERE s.id.kind = :kind",
                        TenantSequence.class)
                .setParameter("kind", kind)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .getResultStream()
                .findFirst()
                .orElse(null);

        if (sequence == null) {
            sequence = new TenantSequence(tenant, kind);
            em.persist(sequence);
            em.flush();
        }

        long value = sequence.getNextValue();
        // Written through dirty checking rather than an UPDATE statement. Safe because
        // the row is locked until this transaction commits -- which is also why the
        // caller must not commit before inserting the row it just numbered.
        sequence.setNextValue(value + 1);
        return value;
    }
}
