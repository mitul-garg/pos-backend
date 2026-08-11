package com.pos.model;

/**
 * Input DTO for {@code POST /api/tenants/verify} (C9, public) — body {@code { token }}.
 *
 * <p>Deliberately a {@code POST} with the token in the body, not a bare {@code GET}
 * carrying it in the URL — an email client's link-preview/scanner bot auto-fetching a
 * {@code GET} link would burn the single-use token before a real person ever clicks
 * it. {@code frontend}'s {@code /verify} page reads {@code ?token=} from its own URL
 * but only calls this endpoint from an explicit button click, never on mount.
 */
public class TenantVerifyForm {

    private String token;

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }
}
