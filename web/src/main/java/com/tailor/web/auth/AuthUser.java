package com.tailor.web.auth;

import java.io.Serializable;
import java.util.UUID;
import org.springframework.security.core.AuthenticatedPrincipal;

/**
 * The signed-in user as stored in the session. {@link #getName()} is the user id, which is what
 * Spring Session indexes sessions by (so {@code DELETE /me} can end all of a user's sessions).
 */
public record AuthUser(UUID id, String email) implements AuthenticatedPrincipal, Serializable {

    @Override
    public String getName() {
        return id.toString();
    }
}
