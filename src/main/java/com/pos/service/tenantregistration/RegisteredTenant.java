package com.pos.service.tenantregistration;

/**
 * The commit-then-email handoff between {@link TenantRegistrationWriter} and
 * {@link TenantRegistrationService} (C9) — deliberately not the JPA entities
 * themselves. {@code AppUserPojo.tenant} is {@code LAZY} and the persistence context that
 * loaded these values closes the moment {@code TenantRegistrationWriter}'s
 * transaction commits, so this carries only the scalar values the email actually
 * needs, read while the transaction was still open. Never serialized to the wire —
 * this is an internal package-private type, not a {@code model} DTO.
 */
record RegisteredTenant(String tenantName, String tenantCode, String adminEmail, String verificationToken) {
}
