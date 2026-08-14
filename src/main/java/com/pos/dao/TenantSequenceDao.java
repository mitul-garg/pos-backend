package com.pos.dao;

import com.pos.pojo.enums.SequenceKind;
import com.pos.pojo.TenantPojo;
import com.pos.pojo.TenantSequencePojo;
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
     * <p><b>Note the sequence query names no tenant.</b> The {@code tenantFilter} on
     * {@link TenantSequencePojo} scopes it, exactly as it scopes every other query — so this
     * can only ever advance the caller's own counter, whatever the {@code tenantId} argument
     * says. That argument exists for the two things the filter cannot do: lock the tenant
     * row, and set the foreign-key column the first time a store asks for a number. Its
     * caller takes it from the id of the parent row (or session) being numbered, which was
     * itself resolved through a filtered read — never straight from a request.
     *
     * <p><b>JPQL rather than {@code em.find(..., PESSIMISTIC_WRITE)}</b> for the sequence
     * row, and not for style: {@code find} would need the composite key, which means
     * naming the tenant id in a query, which is the thing this design does not do.
     *
     * <h2>Why the tenant row is locked first</h2>
     * <b>Found by {@code VariantSequenceIT}, not by reasoning</b> — the first version of
     * this method deadlocked, and its Javadoc confidently explained why it could not.
     *
     * <p>The claim was that a {@code SELECT … FOR UPDATE} matching nothing takes a gap
     * lock, so a second caller creating the same counter would block. It does take a gap
     * lock, and gap locks are <b>shared</b>: both transactions take it, then each tries to
     * insert, and an insert needs an intention lock that conflicts with the <i>other's</i>
     * gap lock. Neither can proceed. MySQL detects it and kills one with
     * "Deadlock found when trying to get lock" — which reached the caller as a 500, on the
     * very first pair of concurrent creates in a fresh store.
     *
     * <p>The fix is the textbook one: <b>take the locks in a fixed order, starting with a
     * row that always exists.</b> Every caller locks the tenant row before touching the
     * sequence, so only one transaction per store is ever inside the section below, the
     * gap lock is uncontended, and there is no cycle to deadlock on.
     *
     * <p>The cost is that a store's QR codes, order numbers and return numbers serialize
     * against each other rather than only against their own kind. For a shop with a
     * handful of tills that is nothing, and it buys a lock order simple enough to state in
     * one sentence — which is worth more here than concurrency nobody is waiting on.
     *
     * <h2>Still true, and still the reason this class exists</h2>
     * <b>Call this in the same transaction as the insert it numbers.</b> Both locks are
     * held until that transaction commits, which is what reserves the value. Take the
     * number in one transaction and insert in another and the locks are released in
     * between — the race, dressed up to look safe. {@code MAX(number) + 1} and a plain
     * read-then-write are no better: MySQL's REPEATABLE READ makes an unlocked
     * {@code SELECT} a snapshot read, so two transactions seeing the same value is defined
     * behaviour rather than misconfiguration.
     */
    public long next(SequenceKind kind, Long tenantId) {
        // The whole of the deadlock fix. Not a read -- the row is already in hand -- but a
        // lock upgrade on a row that is guaranteed to exist, so every caller queues here
        // rather than in the section below where two of them could hold incompatible
        // locks. TenantPojo is unfiltered, so this is unaffected by the tenant filter.
        em.find(TenantPojo.class, tenantId, LockModeType.PESSIMISTIC_WRITE);

        TenantSequencePojo sequence = em.createQuery(
                        "SELECT s FROM TenantSequencePojo s WHERE s.id.kind = :kind",
                        TenantSequencePojo.class)
                .setParameter("kind", kind)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .getResultStream()
                .findFirst()
                .orElse(null);

        if (sequence == null) {
            // First number this store has ever asked for. Safe to insert without racing
            // anyone, because the tenant lock above is held. The primary key
            // (tenant_id, kind) is the backstop if that ever stops being true, and the
            // flush is what makes it fail here rather than at commit.
            sequence = new TenantSequencePojo(tenantId, kind);
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
