package com.tailor.web.auth;

import com.tailor.web.api.ApiException;
import com.tailor.web.config.AppProperties;
import java.io.IOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Google sign-in (PHASE6_SPEC.md sections 4.1 and 9.1): OpenID Connect authorization-code flow with
 * {@code state} and {@code nonce} (Spring Security's defaults), the ID token's signature, issuer,
 * audience, expiry and nonce checked by Spring, and an unverified email rejected before any
 * session exists. The flow is off until {@code app.google.client-id} is set.
 */
@Component
public class GoogleSignIn {

    static final String REGISTRATION_ID = "google";
    static final String START_PATH = "/auth/google";
    static final String CALLBACK_PATH = "/auth/google/callback";
    static final String EMAIL_NOT_VERIFIED = "email_not_verified";

    private static final Logger log = LoggerFactory.getLogger(GoogleSignIn.class);

    private final AppProperties props;
    private final UserRepository users;
    private final SessionSignIn sessions;

    public GoogleSignIn(AppProperties props, UserRepository users, SessionSignIn sessions) {
        this.props = props;
        this.users = users;
        this.sessions = sessions;
    }

    /** Wires the OIDC filters into the security chain. */
    public void configure(HttpSecurity http, ClientRegistrationRepository clients) throws Exception {
        http.oauth2Login(login -> login
                // Never serve Spring's generated /login page; sign-in starts at GET /auth/google.
                .loginPage(START_PATH)
                .authorizationEndpoint(a -> a.authorizationRequestResolver(resolver(clients)))
                .redirectionEndpoint(r -> r.baseUri(CALLBACK_PATH))
                .userInfoEndpoint(u -> u.oidcUserService(idTokenOnly()))
                .successHandler(this::onSuccess)
                .failureHandler((request, response, e) -> {
                    String code = e instanceof OAuth2AuthenticationException oe
                            && EMAIL_NOT_VERIFIED.equals(oe.getError().getErrorCode())
                            ? "EMAIL_NOT_VERIFIED" : "GOOGLE_SIGNIN_FAILED";
                    log.warn("google sign-in rejected: {}", code);
                    response.sendRedirect(props.frontendUrl() + "/signin?error=" + code);
                }));
    }

    /** Only {@code GET /auth/google} starts a flow; other {@code /auth/*} paths are ordinary endpoints. */
    private static OAuth2AuthorizationRequestResolver resolver(ClientRegistrationRepository clients) {
        DefaultOAuth2AuthorizationRequestResolver delegate =
                new DefaultOAuth2AuthorizationRequestResolver(clients, "/auth");
        return new OAuth2AuthorizationRequestResolver() {
            @Override
            public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
                return START_PATH.equals(request.getRequestURI()) ? delegate.resolve(request, REGISTRATION_ID) : null;
            }

            @Override
            public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
                return START_PATH.equals(request.getRequestURI()) ? delegate.resolve(request, clientRegistrationId) : null;
            }
        };
    }

    /**
     * Builds the user from the ID token alone (no userinfo call) and refuses an unverified email:
     * the failure handler runs before any authenticated session is saved.
     */
    private static OAuth2UserService<OidcUserRequest, OidcUser> idTokenOnly() {
        return request -> {
            OidcIdToken token = request.getIdToken();
            Object verified = token.getClaims().get("email_verified");
            boolean ok = Boolean.TRUE.equals(verified) || "true".equals(String.valueOf(verified));
            String email = token.getEmail();
            if (!ok || email == null || email.isBlank()) {
                throw new OAuth2AuthenticationException(new OAuth2Error(EMAIL_NOT_VERIFIED));
            }
            return new DefaultOidcUser(List.of(new SimpleGrantedAuthority("ROLE_USER")), token, IdTokenClaimNames.SUB);
        };
    }

    private void onSuccess(HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response,
            org.springframework.security.core.Authentication authentication) throws IOException {
        OidcUser google = (OidcUser) authentication.getPrincipal();
        try {
            AuthUser user = users.signIn(google.getEmail(), google.getSubject());
            // Replace Spring's OAuth2 authentication with the same session login the email link makes.
            sessions.establish(user, request, response);
            response.sendRedirect(props.frontendUrl());
        } catch (ApiException e) {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
            var session = request.getSession(false);
            if (session != null) {
                session.invalidate();
            }
            response.sendRedirect(props.frontendUrl() + "/signin?error=ACCOUNT_CONFLICT");
        }
    }

    /** The Google client registration, built from {@code app.google.*} when a client id is set. */
    @Configuration
    @ConditionalOnExpression("!'${app.google.client-id:}'.isEmpty()")
    static class Registration {

        @Bean
        ClientRegistrationRepository googleClientRegistrations(AppProperties props) {
            AppProperties.Google g = props.google();
            ClientRegistration registration = ClientRegistration.withRegistrationId(REGISTRATION_ID)
                    .clientName("Google")
                    .clientId(g.clientId())
                    .clientSecret(g.clientSecret())
                    .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .redirectUri(props.publicBaseUrl() + CALLBACK_PATH)
                    .scope("openid", "email", "profile")
                    .authorizationUri(g.authorizationUri())
                    .tokenUri(g.tokenUri())
                    .jwkSetUri(g.jwkSetUri())
                    .issuerUri(g.issuer())
                    .userNameAttributeName(IdTokenClaimNames.SUB)
                    .build();
            return new InMemoryClientRegistrationRepository(registration);
        }
    }
}
