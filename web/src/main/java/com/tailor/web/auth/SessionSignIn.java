package com.tailor.web.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;

/** Starts the server-side session for a signed-in user, whichever way they proved who they are. */
@Component
public class SessionSignIn {

    private final SecurityContextRepository repository = new HttpSessionSecurityContextRepository();
    private final ChangeSessionIdAuthenticationStrategy fixationDefence = new ChangeSessionIdAuthenticationStrategy();

    public void establish(AuthUser user, HttpServletRequest request, HttpServletResponse response) {
        Authentication auth = UsernamePasswordAuthenticationToken.authenticated(
                user, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        if (request.getSession(false) != null) {
            // A new session id at the moment of sign-in, so an id planted beforehand is worthless.
            fixationDefence.onAuthentication(auth, request, response);
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        repository.saveContext(context, request, response);
    }
}
