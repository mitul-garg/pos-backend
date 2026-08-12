package com.pos.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Only the network-free fast path — no test here calls the real Google
 * endpoint, the same reason {@code MailConfigTest} never opens a real SMTP
 * connection to prove {@code JavaMailEmailSender} sends mail. The live round
 * trip (a genuinely fake token gets rejected, a genuinely solved one is
 * accepted) was verified manually against the real site registration — see
 * the peer-review commit message and {@code c9-tenant-registration.md}.
 */
@DisplayName("GoogleRecaptchaVerifier")
class GoogleRecaptchaVerifierTest {

    private final RecaptchaVerifier verifier = new GoogleRecaptchaVerifier("irrelevant-secret");

    @Test
    @DisplayName("rejects a blank token without making a network call")
    void rejectsBlankTokenWithoutCallingOut() {
        assertFalse(verifier.verify(""));
        assertFalse(verifier.verify("   "));
        assertFalse(verifier.verify(null));
    }
}
