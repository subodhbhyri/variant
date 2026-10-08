package com.tailor.web.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Local development only (PHASE6_SPEC.md section 10: "email links printed to the log"). The link
 * is a credential, so this mode must never be used outside a developer's machine; deployed
 * environments set {@code app.mail.mode=ses}.
 */
@Component
@ConditionalOnProperty(name = "app.mail.mode", havingValue = "log", matchIfMissing = true)
public class LogEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(LogEmailSender.class);

    @Override
    public void sendSignInLink(String toEmail, String link) {
        log.warn("DEV MAIL (app.mail.mode=log) sign-in link for {}: {}", toEmail, link);
    }
}
