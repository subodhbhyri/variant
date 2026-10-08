package com.tailor.web.auth;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * {@code app.mail.mode=off}: email sign-in is switched off (PHASE6_SPEC.md sections 4.1 and 10A.3). The
 * request endpoint answers {@code EMAIL_SIGNIN_DISABLED} before it would ever call this, so reaching it is a bug.
 */
@Component
@ConditionalOnProperty(name = "app.mail.mode", havingValue = "off")
public class OffEmailSender implements EmailSender {

    @Override
    public void sendSignInLink(String toEmail, String link) {
        throw new IllegalStateException("email sign-in is off (app.mail.mode=off)");
    }
}
