package com.pos.controller;

import com.pos.exception.TooManyRequestsException;
import com.pos.model.RegistrationAckData;
import com.pos.model.TenantRegistrationData;
import com.pos.model.TenantRegistrationForm;
import com.pos.model.TenantResendVerificationForm;
import com.pos.model.TenantVerificationData;
import com.pos.model.TenantVerifyForm;
import com.pos.service.TenantRegistrationService;
import com.pos.util.RegistrationRateLimiter;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/tenants/register|verify|resend-verification} — public, unauthenticated
 * self-registration (C9, {@code tenant-registration-plan.md} §4). A separate
 * controller from {@code TenantController} rather than three more methods on it:
 * that one is entirely {@code SUPER_ADMIN}-only ({@code TenantService}'s Javadoc),
 * and folding a public surface into it would make "is this endpoint gated?" a
 * per-method question instead of a per-class one.
 *
 * <p>Gated <i>open</i> in {@code SecurityConfig.PUBLIC_PATHS} — the same file that
 * gates {@code TenantController} closed. One file still answers "who can reach
 * this?" for every controller in the application; this is just the file's other
 * answer, "anyone."
 */
@RestController
@RequestMapping("/api/tenants")
public class TenantRegistrationController {

    @Autowired
    private TenantRegistrationService tenantRegistrationService;

    @Autowired
    private RegistrationRateLimiter rateLimiter;

    @Operation(summary = "Register a new store",
               description = "Public, unauthenticated. Creates the tenant "
                       + "PENDING_VERIFICATION and its first ADMIN together, "
                       + "atomically, and emails a verification link. 201 with "
                       + "{ tenantCode, adminEmail } -- never the token, which only "
                       + "ever travels inside the email. 400 with field -> message "
                       + "for a blank/malformed/reserved/duplicate field. 429 if "
                       + "this client IP has registered too many times recently.")
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public TenantRegistrationData register(@RequestBody TenantRegistrationForm form,
                                           HttpServletRequest request) {
        requireWithinLimit("register", request);
        return tenantRegistrationService.register(form);
    }

    @Operation(summary = "Verify a self-registered store",
               description = "Public, unauthenticated. POST, not GET, so an email "
                       + "client's link-preview bot can't burn the single-use token "
                       + "before a person clicks it. Unknown token, expired token, "
                       + "and a token whose tenant is no longer PENDING_VERIFICATION "
                       + "all answer the identical 400 -- no distinguishing which. "
                       + "On success, flips the tenant ACTIVE and returns "
                       + "{ tenantCode } to redirect to login with.")
    @PostMapping("/verify")
    public TenantVerificationData verify(@RequestBody TenantVerifyForm form) {
        return tenantRegistrationService.verify(form);
    }

    @Operation(summary = "Resend a store's verification email",
               description = "Public, unauthenticated. Re-mints the token for a "
                       + "{ tenantCode, adminEmail } pair that matches a "
                       + "PENDING_VERIFICATION tenant's admin. Answers the identical "
                       + "acknowledgement whether or not the pair matched anything "
                       + "real -- no enumeration oracle. 429 if this client IP has "
                       + "asked too many times recently.")
    @PostMapping("/resend-verification")
    public RegistrationAckData resendVerification(@RequestBody TenantResendVerificationForm form,
                                                   HttpServletRequest request) {
        requireWithinLimit("resend-verification", request);
        return tenantRegistrationService.resendVerification(form);
    }

    /**
     * {@code verify} is deliberately not rate-limited here — a token is single-use
     * and expires in 24h on its own, unlike {@code register}/{@code resend
     * -verification}, which each do a real DB write and (on the paths that reach it)
     * send an email, `tenant-registration-plan.md` §4's actual abuse concern.
     *
     * <p>Keyed on the action name plus the caller's IP, not IP alone, so the two
     * limited endpoints get independent budgets — see {@code RegistrationRateLimiter}
     * 's Javadoc for why that's a deliberate choice, not an oversight.
     */
    private void requireWithinLimit(String action, HttpServletRequest request) {
        String key = action + ":" + request.getRemoteAddr();
        if (!rateLimiter.allow(key)) {
            throw new TooManyRequestsException();
        }
    }
}
