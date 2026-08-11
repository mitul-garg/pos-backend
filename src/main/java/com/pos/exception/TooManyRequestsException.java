package com.pos.exception;

/**
 * {@link com.pos.util.RegistrationRateLimiter} refusing a request — maps to
 * <b>429</b> (C9). One fixed message, no constructor argument, the same device
 * {@link InvalidCredentialsException} uses and for the same reason: which of the two
 * rate-limited endpoints tripped, and by how much, is not this exception's to say —
 * the caller gets "try again later," not a number that invites probing the exact
 * threshold.
 */
public class TooManyRequestsException extends ApiException {

    public static final String MESSAGE = "Too many requests. Try again later.";

    public TooManyRequestsException() {
        super(MESSAGE);
    }
}
