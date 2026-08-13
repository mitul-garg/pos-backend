package com.pos.service;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import com.pos.dao.TenantDao;
import com.pos.pojo.TenantPojo;
import com.pos.util.MaxLength;

/**
 * The tenant-code rule (required / format / reserved / duplicate) — shared between
 * {@link TenantService#create} (the {@code SUPER_ADMIN} platform path, C8) and
 * {@link com.pos.service.tenantregistration.TenantRegistrationWriter#register} (the public
 * self-registration path, C9). Extracted rather than duplicated a second time: a code is
 * either safe to claim or it isn't, regardless of which of the two ways a {@code TenantPojo}
 * row gets minted, and `tenant-registration-plan.md` §4 says explicitly that registration
 * "reuses {@code TenantService.create}'s validation shape" for exactly this reason.
 *
 * <p><b>Public rather than package-private</b>, as of peer-review Phase 2's
 * {@code service.tenantregistration} subpackage split — its two callers no longer share a
 * package, so package-private visibility stopped being available, not merely stylistic.
 * Still has no business being part of the platform surface's own public API in spirit; the
 * two callers listed above remain the only ones.
 */
public final class TenantCodeRule {

    static final String CODE_REQUIRED = "Tenant code is required";
    static final String CODE_FORMAT = "Use lowercase letters, numbers and hyphens only";
    static final String CODE_TAKEN = "That tenant code is already taken";

    /** Lowercase letters, digits and hyphens, not starting with a hyphen. */
    private static final Pattern CODE_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-]*$");

    private TenantCodeRule() {
    }

    /**
     * Normalizes {@code rawCode} and validates it, recording a failure under
     * {@code fieldKey} in {@code errors}. The field key is a parameter, not a
     * constant, because the two callers' wire contracts disagree on it —
     * {@code TenantForm.code} for the platform path, {@code TenantRegistrationForm
     * .tenantCode} for the public one — so the same rule has to be able to report
     * against either name.
     *
     * @return the normalized (trimmed, lower-cased) value regardless of validity, so
     *         a caller can still read back what would have been stored — matching
     *         {@code TenantService.create}'s existing contract for this method
     */
    public static String validate(TenantDao tenantDao, String rawCode, String fieldKey, Map<String, String> errors) {
        String value = rawCode == null ? "" : rawCode.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) {
            errors.put(fieldKey, CODE_REQUIRED);
        } else if (!CODE_PATTERN.matcher(value).matches()) {
            errors.put(fieldKey, CODE_FORMAT);
        } else if (TenantPojo.RESERVED_CODES.contains(value)) {
            errors.put(fieldKey, "\"" + value + "\" is reserved and cannot be used as a tenant code");
        } else if (tenantDao.findByCode(value) != null) {
            errors.put(fieldKey, CODE_TAKEN);
        }
        // Peer-review Phase 1: CODE_PATTERN bounds the character set but not the length,
        // and tenant.code is VARCHAR(64) -- without this, an overlong-but-otherwise-legal
        // code reached MySQL's data-too-long error unmapped. Checked last and
        // unconditionally (not another `else if` above) so it still reports even when
        // the same value also failed an earlier check.
        MaxLength.check(errors, fieldKey, "Tenant code", value, 64);
        return value;
    }
}
