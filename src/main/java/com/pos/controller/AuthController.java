package com.pos.controller;

import com.pos.model.LoginData;
import com.pos.model.LoginForm;
import com.pos.model.SessionUserData;
import com.pos.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/auth} — the three endpoints in requirements.md section 9.
 *
 * <p>Thin by design (CONVENTIONS.md): map the request, delegate, map the response. Every
 * decision about who may log in, and every status code, belongs to {@code AuthService}
 * and {@code ApiExceptionHandler} respectively.
 *
 * <p><b>Note what is absent from these signatures.</b> Nothing takes a {@code tenantId},
 * and {@code me} takes no token — the tenant comes from the request's token and the
 * caller comes from the {@code SecurityContext} that {@code JwtAuthenticationFilter}
 * already populated. A parameter is something a caller can set, and identity is not.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @Autowired
    private AuthService authService;

    /**
     * No {@code @Valid}, deliberately — see {@link LoginForm}. A blank tenant code has to
     * fail as a login (401, generic), not as a malformed request (400, field-level),
     * because the difference between the two is exactly what the uniform 401 hides.
     */
    @Operation(summary = "Log in",
               description = "Resolves (tenantCode, username) to a user and returns a bearer "
                       + "token. The reserved code `platform` selects the SUPER_ADMIN "
                       + "namespace. Unknown tenant, unknown username and wrong password are "
                       + "one indistinguishable 401; a deactivated user or a suspended tenant "
                       + "is a 403, reachable only once the password is correct.")
    @PostMapping("/login")
    public LoginData login(@RequestBody LoginForm form) {
        return authService.login(form);
    }

    /**
     * {@code 204}, and nothing else happens.
     *
     * <p>Honest about being a no-op: the token is the session, so there is no server-side
     * state to discard — <b>the issued token stays valid until it expires</b>, and the
     * client dropping it is what ends the session. Revocation would need a denylist, a
     * store to keep it in, and a check on every request; backend-plan.md section 11 keeps
     * that open rather than half-building it.
     *
     * <p>The endpoint exists anyway because the contract says so, because it is where
     * that denylist will attach, and because it gives the frontend one place to call.
     * It requires a token for the same reason: the day it does something, it will need to
     * know whose session it is ending.
     */
    @Operation(summary = "Log out",
               description = "Acknowledges the client discarding its token. The token itself "
                       + "remains valid until it expires — there is no server-side session "
                       + "to end, and no revocation list yet.")
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout() {
        // Intentionally empty. See the Javadoc -- an empty body here is the design, and a
        // future denylist is what fills it.
    }

    /**
     * Restores identity <b>and</b> tenant after a page refresh; the frontend's
     * {@code AuthContext} awaits it on mount before any screen fetches.
     *
     * <p>Reads a session the filter has already resolved, which means it also re-checked
     * the account and the tenant against the database on this very request. That is the
     * upgrade over the mock, where {@code me()} was the <i>only</i> place status was
     * re-checked and a suspended store's open tab kept working until someone refreshed.
     */
    @Operation(summary = "Current session",
               description = "The authenticated user and their tenant, read fresh from the "
                       + "database on every call. A platform SUPER_ADMIN reports a null "
                       + "tenantId.")
    @GetMapping("/me")
    public SessionUserData me() {
        return authService.currentSession();
    }
}
