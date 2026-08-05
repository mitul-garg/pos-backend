package com.pos.model;

/**
 * Output DTO for {@code POST /api/auth/login} — {@code {token, user}}, exactly the shape
 * the frontend's mock returns today (requirements.md section 9), so swapping the mock
 * for HTTP in C9 is a body-only change.
 *
 * <p>The token is returned in the body rather than set as a cookie. Deliberate: the
 * frontend already persists it in {@code localStorage} and sends it as an
 * {@code Authorization} header, and a stateless bearer token that never rides on a
 * cookie is a request that CSRF cannot forge — which is why the security chain can
 * disable CSRF protection honestly rather than as a shortcut.
 */
public class LoginData {

    private final String token;
    private final SessionUserData user;

    public LoginData(String token, SessionUserData user) {
        this.token = token;
        this.user = user;
    }

    public String getToken() {
        return token;
    }

    public SessionUserData getUser() {
        return user;
    }
}
