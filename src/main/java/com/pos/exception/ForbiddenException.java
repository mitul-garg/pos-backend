package com.pos.exception;

/**
 * Maps to <b>403</b>: a deactivated user, a suspended tenant, or a role that may not
 * reach this endpoint.
 *
 * <p>Unlike the 401 this <i>can</i> afford a specific message — but only because it is
 * unreachable until the password has already been proved correct, so it leaks nothing
 * to someone guessing. That guarantee depends entirely on ordering:
 * <b>the account-status checks must run after the password check, never before.</b>
 * Reversing them turns a specific 403 into an account-existence oracle.
 */
public class ForbiddenException extends ApiException {

    public ForbiddenException(String message) {
        super(message);
    }
}
