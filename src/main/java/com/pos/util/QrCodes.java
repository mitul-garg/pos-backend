package com.pos.util;

/**
 * The QR payload's shape, in one place (C5).
 *
 * <p>{@code POS-QR-{tenantId}-{000001}} — identical to the mock's
 * {@code generateQrCode()}, except that the tenant segment is a database id rather than
 * {@code t1}. A label printed against the mock does not scan against the backend, which is
 * correct: they are different stores' data.
 *
 * <p><b>The tenant segment is what makes printed labels globally distinct</b>
 * (requirements.md section 6). The counter alone is not: every tenant's run starts at 1,
 * so two stores would otherwise both print {@code 000001}. Lookup is tenant-scoped besides,
 * so a foreign label 404s rather than resolving — but the segment means the two codes are
 * not even the same string, which matters the day these values are read by something that
 * is not this application.
 *
 * <p>The value is data, and only data. The QR <i>image</i> is rendered from it on the
 * client and never stored — a Phase-2 decision that pays off at deploy time, because
 * nothing needs a writable disk.
 */
public final class QrCodes {

    /** Matches {@code QR_CODE_PREFIX} in the frontend's {@code domain/constants.js}. */
    public static final String PREFIX = "POS-QR";

    /**
     * Six digits, so the run reads as a fixed-width label rather than drifting in length
     * as a store's catalogue grows. It is padding, not a ceiling: a tenant's 1,000,000th
     * variant produces a seven-digit code, which is still unique and still scans.
     */
    private static final String FORMAT = PREFIX + "-%d-%06d";

    private QrCodes() {
    }

    public static String payload(Long tenantId, long sequence) {
        return FORMAT.formatted(tenantId, sequence);
    }

    /**
     * The fallback SKU for a variant created without one — the frontend's add form shows
     * "auto-generated" and sends an empty string.
     *
     * <p>Derived from the <b>same sequence value as the QR code</b>, so the two agree and
     * so it inherits that value's uniqueness within the tenant. The mock used the variant's
     * own surrogate id, which does not exist yet at this point in an
     * {@code AUTO_INCREMENT} world — the row has to be built before the database will
     * name it.
     */
    public static String fallbackSku(long sequence) {
        return "SKU-%06d".formatted(sequence);
    }
}
