package com.pos.service;

import java.time.Instant;
import java.util.Locale;

import com.pos.config.AppProperties;
import com.pos.dao.TenantDao;
import com.pos.exception.ValidationException;
import com.pos.model.RegistrationAckData;
import com.pos.model.TenantRegistrationData;
import com.pos.model.TenantRegistrationForm;
import com.pos.model.TenantResendVerificationForm;
import com.pos.model.TenantVerificationData;
import com.pos.model.TenantVerifyForm;
import com.pos.pojo.Tenant;
import com.pos.pojo.TenantStatus;
import com.pos.util.EmailSender;
import com.pos.util.Honeypot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The public self-registration surface (C9, {@code tenant-registration-plan.md} §4) —
 * {@code register}/{@code verify}/{@code resendVerification}, reachable with no
 * session at all, unlike every other service in this codebase. Gated public by
 * {@code SecurityConfig}'s matchers (C9(e)), not by anything in here — the same
 * division of labour {@code TenantService}'s Javadoc already establishes for the
 * opposite case (a role-gated surface with no check of its own).
 *
 * <p><b>{@code register}/{@code resendVerification} are deliberately NOT
 * {@code @Transactional}.</b> Both need their write to have genuinely committed
 * before the verification email goes out — see {@link TenantRegistrationWriter}'s
 * Javadoc for why that needs a second bean, and why annotating a method here would
 * silently break the ordering: Spring would fold the writer's transaction into this
 * one (or start none at all if nothing outside ever opens one), so nothing would
 * actually commit until this method returns — i.e., not until after the email
 * attempt anyway, defeating the entire reason the write and the send are split.
 * {@code verify} has no such constraint (it sends no email) and is a plain
 * {@code @Transactional} method here, same shape as everywhere else in this codebase.
 */
@Service
public class TenantRegistrationService {

    private static final Logger log = LoggerFactory.getLogger(TenantRegistrationService.class);

    /**
     * One message for unknown token, expired token, and a token whose tenant already
     * left {@code PENDING_VERIFICATION} — don't distinguish any of these, same
     * no-enumeration reasoning as login's uniform 401.
     */
    static final String INVALID_OR_EXPIRED_TOKEN = "This verification link is invalid or has expired.";

    /** Verbatim from the mock's {@code RESEND_ACK} — identical whether or not anything matched. */
    static final String RESEND_ACK = "If that store is awaiting verification, we've re-sent the email.";

    private final TenantRegistrationWriter writer;
    private final TenantDao tenantDao;
    private final EmailSender emailSender;
    private final AppProperties props;

    @Autowired
    public TenantRegistrationService(TenantRegistrationWriter writer, TenantDao tenantDao,
                                     EmailSender emailSender, AppProperties props) {
        this.writer = writer;
        this.tenantDao = tenantDao;
        this.emailSender = emailSender;
        this.props = props;
    }

    /**
     * {@code POST /api/tenants/register}. A honeypot trip resolves with the identical
     * success shape a real registration gets — no tenant/admin row, no email, no
     * hint that anything was detected (see {@link Honeypot}'s Javadoc) — so it never
     * even reaches {@link TenantRegistrationWriter}, and therefore touches the
     * database not at all.
     */
    public TenantRegistrationData register(TenantRegistrationForm form) {
        if (Honeypot.isTripped(form.getWebsite())) {
            return new TenantRegistrationData(normalize(form.getTenantCode()), blankToNull(form.getAdminEmail()));
        }

        RegisteredTenant registered = writer.register(form);
        sendVerificationEmail(registered);
        return new TenantRegistrationData(registered.tenantCode(), registered.adminEmail());
    }

    /**
     * {@code POST /api/tenants/verify}. A plain {@code @Transactional} read-then-write
     * is safe here — unlike {@link #register}/{@link #resendVerification}, this never
     * sends an email, so there's no commit-ordering constraint pulling it into a
     * second bean.
     */
    @Transactional
    public TenantVerificationData verify(TenantVerifyForm form) {
        String token = blankToNull(form.getToken());
        Tenant tenant = token == null ? null : tenantDao.findByVerificationToken(token);

        // Must also require the CURRENT status to still be PENDING_VERIFICATION, not
        // just a matching, unexpired token (frontend/BUGS.md #17): the token is only
        // cleared BY a successful verify, so it stays live until then -- without this
        // check, a SUPER_ADMIN suspending a still-pending tenant wouldn't actually
        // stop it, because the owner clicking their still-unexpired link afterward
        // would silently flip it back to ACTIVE, undoing the suspension.
        if (tenant == null
                || tenant.getStatus() != TenantStatus.PENDING_VERIFICATION
                || tenant.getVerificationExpiresAt() == null
                || tenant.getVerificationExpiresAt().isBefore(Instant.now())) {
            throw new ValidationException(INVALID_OR_EXPIRED_TOKEN);
        }

        tenant.setStatus(TenantStatus.ACTIVE);
        tenant.setVerificationToken(null);
        tenant.setVerificationExpiresAt(null);
        return new TenantVerificationData(tenant.getCode());
    }

    /**
     * {@code POST /api/tenants/resend-verification}. Same non-transactional shape as
     * {@link #register} and for the identical reason — see the class Javadoc.
     */
    public RegistrationAckData resendVerification(TenantResendVerificationForm form) {
        RegisteredTenant registered = writer.resendVerification(form.getTenantCode(), form.getAdminEmail());
        if (registered != null) {
            sendVerificationEmail(registered);
        }
        // Identical acknowledgement whether or not anything matched -- no enumeration
        // oracle, matching every other auth-adjacent endpoint in this codebase.
        return new RegistrationAckData(RESEND_ACK);
    }

    /**
     * Fired only after the write that produced {@code registered} has committed —
     * see the class Javadoc. A slow/failed send must never surface to the caller or
     * roll anything back: {@code resendVerification} is this feature's own recovery
     * path for a failed send, so log-and-continue is the correct response here, not
     * a retry loop or a propagated exception.
     */
    private void sendVerificationEmail(RegisteredTenant registered) {
        String verifyUrl = props.getFrontendBaseUrl() + "/verify?token=" + registered.verificationToken();
        String subject = "Verify your PoS store — " + registered.tenantName();
        String body = "Click the link below to verify \"" + registered.tenantName()
                + "\" and activate your account:\n\n" + verifyUrl
                + "\n\nThis link expires in 24 hours. If you didn't request this, "
                + "you can ignore this email.";
        try {
            emailSender.send(registered.adminEmail(), subject, body);
        } catch (RuntimeException ex) {
            log.error("Failed to send verification email for tenant {}", registered.tenantCode(), ex);
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
