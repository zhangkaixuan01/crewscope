package io.crewscope.server.security.oidc;

import io.crewscope.application.identity.LoginDefense;
import io.crewscope.application.identity.OidcAccountAuthenticationService;
import io.crewscope.application.identity.OidcAuthenticationCommand;
import io.crewscope.application.identity.OidcAuthenticationException;
import io.crewscope.server.api.ApiCorrelationIds;
import io.crewscope.server.config.BrowserOrganizationAnchor;
import io.crewscope.server.config.RegistrationProperties;
import io.crewscope.server.security.session.BrowserSessionLifecycle;
import io.crewscope.domain.identity.ExternalAccountIdentity;
import io.crewscope.domain.shared.id.OrganizationId;
import java.net.URI;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.server.WebFilterExchange;
import org.springframework.security.web.server.authentication.ServerAuthenticationSuccessHandler;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Turns one completed OIDC code exchange into a CrewScope Session (M12-I01): claims become the
 * {@link ExternalAccountIdentity}, the use-case service provisions or re-authenticates, and the
 * browser lands on the SPA root. No IdP token is stored or forwarded — the code exchange and the
 * Session are two disjoint trust domains. Business failures redirect with a fixed
 * {@code sso_error} code; the codes describe deployment state and never echo subject or claims.
 */
public final class OidcAuthenticationSuccessHandler implements ServerAuthenticationSuccessHandler {

    private static final String ROOT_PATH = "/";
    private static final String LOGIN_ERROR_PATH = "/login?sso_error=";

    private final OidcAccountAuthenticationService service;
    private final BrowserSessionLifecycle sessions;
    private final LoginDefense defense;
    private final RegistrationProperties registration;
    private final BrowserOrganizationAnchor organizationAnchor;

    public OidcAuthenticationSuccessHandler(
            OidcAccountAuthenticationService service,
            BrowserSessionLifecycle sessions,
            LoginDefense defense,
            RegistrationProperties registration,
            BrowserOrganizationAnchor organizationAnchor) {
        this.service = Objects.requireNonNull(service, "service");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.defense = Objects.requireNonNull(defense, "defense");
        this.registration = Objects.requireNonNull(registration, "registration");
        this.organizationAnchor = Objects.requireNonNull(organizationAnchor, "organizationAnchor");
    }

    @Override
    public Mono<Void> onAuthenticationSuccess(
            WebFilterExchange exchange, Authentication authentication) {
        ServerWebExchange webExchange = exchange.getExchange();
        // The OAuth2 login filter hands its success handlers an OAuth2AuthenticationToken (the
        // manager's internal OAuth2LoginAuthenticationToken never crosses this boundary), so the
        // registration id comes from the token itself.
        if (!(authentication instanceof OAuth2AuthenticationToken token)
                || !(token.getPrincipal() instanceof OidcUser user)) {
            return redirect(webExchange, LOGIN_ERROR_PATH + "denied");
        }
        OidcAuthenticationCommand command = command(token, user, webExchange);
        return Mono.fromCompletionStage(service.authenticate(command))
                .flatMap(
                        result -> sessions
                                .establish(
                                        webExchange,
                                        result.resolution().account(),
                                        command.identity().provider())
                                .then(Mono.fromCompletionStage(
                                        defense.recordSuccess(result.resolution().account().id())))
                                .then(redirect(webExchange, ROOT_PATH)))
                .onErrorResume(
                        OidcAuthenticationException.class,
                        failure -> redirect(
                                webExchange, LOGIN_ERROR_PATH + errorCode(failure.failure())))
                .onErrorResume(
                        error -> {
                            // The use-case service is the only trusted interpreter of
                            // federated outcomes; anything reaching this handler past it is
                            // an infrastructure fault, still never a bypass into a Session.
                            return redirect(webExchange, LOGIN_ERROR_PATH + "unavailable");
                        });
    }

    private OidcAuthenticationCommand command(
            OAuth2AuthenticationToken token, OidcUser user, ServerWebExchange exchange) {
        Map<String, Object> claims = user.getClaims();
        ExternalAccountIdentity identity = new ExternalAccountIdentity(
                token.getAuthorizedClientRegistrationId(),
                user.getSubject(),
                text(claims.get("name")),
                text(claims.get("preferred_username")),
                text(claims.get("email")),
                Boolean.TRUE.equals(claims.get("email_verified")));
        return new OidcAuthenticationCommand(
                identity,
                registration.getMode(),
                federatedAnchor(),
                ApiCorrelationIds.resolve(exchange),
                Optional.empty());
    }

    private OrganizationId federatedAnchor() {
        return organizationAnchor
                .oidcOrganizationId()
                .orElseThrow(() -> new AuthenticationServiceException(
                        "OIDC authentication has no organization anchor"));
    }

    private static String errorCode(OidcAuthenticationException.Failure failure) {
        return switch (failure) {
            case DENIED -> "denied";
            case LOCKED -> "locked";
            case REGISTRATION_BLOCKED -> "registration_blocked";
            case UNAVAILABLE -> "unavailable";
        };
    }

    private static Optional<String> text(Object claim) {
        if (claim instanceof String value && !value.isBlank()) {
            return Optional.of(value.strip());
        }
        return Optional.empty();
    }

    private static Mono<Void> redirect(ServerWebExchange exchange, String location) {
        exchange.getResponse().setStatusCode(HttpStatus.FOUND);
        exchange.getResponse().getHeaders().setLocation(URI.create(location));
        return exchange.getResponse().setComplete();
    }
}
