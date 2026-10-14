package io.crewscope.server.security.oidc;

import java.net.URI;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.server.WebFilterExchange;
import org.springframework.security.web.server.authentication.ServerAuthenticationFailureHandler;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Protocol-level OIDC failures (bad state, tampered nonce, unsigned or wrongly signed ID token,
 * rejected redirect) land on the login page with one fixed code (M12-I01). The exception text
 * stays server-side: echoing framework validator messages to the browser would leak internals
 * to whoever is probing the redirect flow.
 */
public final class OidcAuthenticationFailureHandler implements ServerAuthenticationFailureHandler {

    private static final String LOGIN_ERROR_PATH = "/login?sso_error=denied";

    @Override
    public Mono<Void> onAuthenticationFailure(
            WebFilterExchange exchange, AuthenticationException exception) {
        Objects.requireNonNull(exception, "exception");
        ServerWebExchange webExchange = exchange.getExchange();
        // A committed response already carries an earlier redirect (the success path may have
        // written one before a downstream step failed); writing headers now would tear the
        // response into a 500 instead of letting the first redirect reach the browser.
        if (webExchange.getResponse().isCommitted()) {
            return Mono.empty();
        }
        webExchange.getResponse().setStatusCode(HttpStatus.FOUND);
        webExchange.getResponse().getHeaders().setLocation(URI.create(LOGIN_ERROR_PATH));
        return webExchange.getResponse().setComplete();
    }
}
