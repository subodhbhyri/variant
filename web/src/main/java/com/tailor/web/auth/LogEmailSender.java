package com.tailor.web.auth;

import com.tailor.web.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Development and smoke runs only (PHASE6_SPEC.md section 10: "email links printed to the log"). The link
 * is a credential, so this mode refuses to start when {@code app.env} is {@code prod}; production sends
 * mail through SES ({@link SesEmailSender}).
 */
@Component
@ConditionalOnProperty(name = "app.mail.mode", havingValue = "log", matchIfMissing = true)
public class LogEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(LogEmailSender.class);

    public LogEmailSender(AppProperties props) {
        if ("prod".equalsIgnoreCase(props.env())) {
            throw new IllegalStateException("app.mail.mode=log prints sign-in links to the log and is not allowed in prod;"
                    + " set app.mail.mode=ses");
        }
    }

    @Override
    public void sendSignInLink(String toEmail, String link) {
        log.warn("DEV MAIL (app.mail.mode=log) sign-in link for {}: {}", toEmail, link);
    }
}
