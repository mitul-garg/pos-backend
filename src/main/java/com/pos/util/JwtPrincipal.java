package com.pos.util;

import com.pos.pojo.enums.Role;

/**
 * What a verified token claims. Note the word <i>claims</i>: this is the caller's
 * assertion about themselves, cryptographically proved to have come from us and to be
 * unexpired, and nothing more.
 *
 * <p><b>It is not the session.</b> {@code AuthService} re-reads the user row on every
 * request and builds the session from that, because a token issued twelve hours ago
 * cannot know that the account was deactivated or the tenant suspended since. Compare
 * {@code com.pos.model.SessionUserData}, which is the database's answer.
 *
 * @param userId   the {@code sub} claim — the {@code app_user} id
 * @param tenantId the tenant the token was issued for, or {@code null} for a platform
 *                 {@code SUPER_ADMIN}, which belongs to no tenant
 * @param role     the role at issue time; the stored row wins if they have since diverged
 */
public record JwtPrincipal(Long userId, Long tenantId, Role role) {
}
