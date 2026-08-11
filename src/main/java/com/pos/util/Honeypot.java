package com.pos.util;

/**
 * The honeypot check for {@code POST /api/tenants/register} (C9,
 * {@code tenant-registration-plan.md} §4) — a hidden {@code website} field real users
 * never see or fill (CSS-hidden on the frontend); a naive scraper that fills every field
 * it finds in the HTML, blind to CSS, fills this one too.
 *
 * <p><b>A trip is silently accepted-and-dropped, never reported.</b>
 * {@code TenantRegistrationService.register} (C9(d)) checks {@link #isTripped}, and on a
 * trip returns the identical success shape a real registration gets — no tenant/admin
 * row, no email, but never a 400 or a distinguishable error — so a scraping bot has
 * nothing to learn from and never discovers which field to leave blank. That behavior
 * belongs to the service, not this class; this is purely the one-line predicate, pulled
 * out so it has a name and a unit test rather than living as an inline
 * {@code != null && !isBlank()} a future reader has to reverse-engineer the intent of.
 *
 * <p><b>What this does not catch</b>: a bot written specifically against this app, one
 * that inspects the CSS and knows which field is fake, sails past it — this is a filter
 * for generic/naive scraping, not a targeted attacker. {@link RegistrationRateLimiter}
 * is the backstop for that case: it caps how many requests a single IP can make
 * regardless of whether this check ever trips.
 *
 * <p>Stateless and trivial enough that this is a static method, not a bean — unlike
 * {@link RegistrationRateLimiter}, there is no per-key state to own.
 */
public final class Honeypot {

    private Honeypot() {
    }

    /** @return {@code true} if {@code value} is non-blank — i.e. something filled it in */
    public static boolean isTripped(String value) {
        return value != null && !value.isBlank();
    }
}
