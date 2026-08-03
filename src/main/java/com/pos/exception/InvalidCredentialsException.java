package com.pos.exception;

/**
 * Maps to <b>401</b> with one fixed message, whatever actually went wrong.
 *
 * <p>Unknown tenant code, blank tenant code, unknown username within that tenant, and
 * wrong password all produce this and are indistinguishable to the caller.
 *
 * <p><b>The uniformity is a security property, not a UX compromise.</b> Naming the
 * tenant would confirm which tenants exist; naming the username would confirm which
 * accounts do. The constructor takes no message precisely so no future caller can
 * helpfully narrow it — the message is not this class's to vary.
 *
 * <p>String matches the frontend's {@code INVALID_CREDENTIALS} in
 * {@code authService.js} verbatim, so mock and real backend read identically.
 */
public class InvalidCredentialsException extends ApiException {

    public static final String MESSAGE = "Invalid tenant, username, or password";

    public InvalidCredentialsException() {
        super(MESSAGE);
    }
}
