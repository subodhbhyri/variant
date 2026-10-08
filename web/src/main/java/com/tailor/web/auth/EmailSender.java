package com.tailor.web.auth;

/** Delivers a sign-in link. The implementation is chosen by {@code app.mail.mode}. */
public interface EmailSender {

    /** @throws RuntimeException if the message could not be handed to the mail system */
    void sendSignInLink(String toEmail, String link);
}
