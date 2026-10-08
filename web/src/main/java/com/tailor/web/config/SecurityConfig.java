package com.tailor.web.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tailor.web.api.ApiError;
import com.tailor.web.auth.GoogleSignIn;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * PHASE6_SPEC.md section 9.1: sessions are server-side (Spring Session in PostgreSQL; cookie
 * flags and the 14-day idle expiry are in application.yml), CSRF protection covers every
 * state-changing request with a double-submit token the SPA can read, and the only CORS origin
 * is the app's.
 */
@Configuration
@EnableWebSecurity
@ConditionalOnProperty(name = "app.role", havingValue = "api")
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, AppProperties props,
            ObjectProvider<ClientRegistrationRepository> clients, GoogleSignIn google, ObjectMapper json)
            throws Exception {
        CookieCsrfTokenRepository csrfTokens = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfTokens.setCookieCustomizer(cookie -> {
            cookie.secure(props.cookie().secure()).sameSite("Lax");
            if (props.cookie().domain() != null && !props.cookie().domain().isBlank()) {
                cookie.domain(props.cookie().domain());
            }
        });

        http
                // The raw token, not the BREACH-masked one: the SPA copies the cookie into a header.
                .csrf(csrf -> csrf.csrfTokenRepository(csrfTokens)
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
                .cors(Customizer.withDefaults())
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                // An anonymous 401 must not create a session row; there is no "return to the page you wanted".
                .requestCache(cache -> cache.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/auth/**", "/config", "/healthz", "/v3/api-docs", "/v3/api-docs/**",
                                "/actuator/health", "/actuator/health/**").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, e) -> writeError(json, response,
                                HttpStatus.UNAUTHORIZED, new ApiError("UNAUTHENTICATED", "Sign in to continue.")))
                        .accessDeniedHandler((request, response, e) -> writeError(json, response,
                                HttpStatus.FORBIDDEN, e instanceof org.springframework.security.web.csrf.CsrfException
                                        ? new ApiError("CSRF", "The request is missing a valid CSRF token.")
                                        : new ApiError("FORBIDDEN", "Not allowed."))));

        if (clients.getIfAvailable() != null) {
            google.configure(http, clients.getObject());
        }
        return http.build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(AppProperties props) {
        URI front = URI.create(props.frontendUrl());
        String origin = front.getScheme() + "://" + front.getAuthority();
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(List.of(origin));
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Content-Type", "X-XSRF-TOKEN", "Idempotency-Key", "Accept"));
        cors.setAllowCredentials(true);
        cors.setMaxAge(600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        return source;
    }

    private static void writeError(ObjectMapper json, HttpServletResponse response, HttpStatus status, ApiError body)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        json.writeValue(response.getOutputStream(), body);
    }

    /** Makes sure the XSRF-TOKEN cookie is sent: Spring Security only creates the token when asked. */
    private static final class CsrfCookieFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
            if (token != null) {
                token.getToken();
            }
            chain.doFilter(request, response);
        }
    }
}
