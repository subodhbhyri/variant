package com.tailor.web.auth;

import com.tailor.web.config.AppProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.Body;
import software.amazon.awssdk.services.sesv2.model.Content;
import software.amazon.awssdk.services.sesv2.model.Destination;
import software.amazon.awssdk.services.sesv2.model.EmailContent;
import software.amazon.awssdk.services.sesv2.model.Message;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;

/**
 * Sends the sign-in link through Amazon SES (PHASE6_SPEC.md section 10): from the verified sending domain
 * ({@code app.mail.from}), plain text and HTML. The task role grants {@code ses:SendEmail} on the domain
 * identity only. The address and the link are never logged.
 */
@Component
@ConditionalOnProperty(name = "app.mail.mode", havingValue = "ses")
public class SesEmailSender implements EmailSender {

    static final String SUBJECT = "Your Resume Tailor sign-in link";

    private final AppProperties props;
    private volatile SesV2Client client;

    public SesEmailSender(AppProperties props) {
        this.props = props;
    }

    /** For tests: a client that is not the real SES. */
    SesEmailSender(AppProperties props, SesV2Client client) {
        this.props = props;
        this.client = client;
    }

    private SesV2Client client() {
        SesV2Client c = client;
        if (c == null) {
            synchronized (this) {
                if (client == null) {
                    client = SesV2Client.create(); // region and credentials from the environment / task role
                }
                c = client;
            }
        }
        return c;
    }

    @Override
    public void sendSignInLink(String toEmail, String link) {
        String text = "Open this link to sign in to Resume Tailor:\n\n" + link + "\n\n"
                + "It works once and expires in 15 minutes. If you didn't ask for it, you can ignore this email.\n";
        String html = "<p>Open this link to sign in to Resume Tailor:</p><p><a href=\"" + link + "\">Sign in</a></p>"
                + "<p>It works once and expires in 15 minutes. If you didn't ask for it, you can ignore this email.</p>";
        client().sendEmail(SendEmailRequest.builder()
                .fromEmailAddress(props.mail().from())
                .destination(Destination.builder().toAddresses(toEmail).build())
                .content(EmailContent.builder().simple(Message.builder()
                        .subject(Content.builder().data(SUBJECT).charset("UTF-8").build())
                        .body(Body.builder()
                                .text(Content.builder().data(text).charset("UTF-8").build())
                                .html(Content.builder().data(html).charset("UTF-8").build()).build())
                        .build()).build())
                .build());
    }
}
