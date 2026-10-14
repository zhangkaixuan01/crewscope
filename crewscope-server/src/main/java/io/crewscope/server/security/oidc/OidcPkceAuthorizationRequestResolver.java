package io.crewscope.server.security.oidc;

import java.util.Objects;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.server.DefaultServerOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.server.ServerOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Authorization-request resolver with PKCE (S256) forced on for every registration (M12-I01):
 * Spring only adds the challenge by default for public clients, and a confidential client must
 * still bind the authorization code to the browser round-trip. There is deliberately no
 * opt-out switch — deployments that cannot carry a code_challenge fail closed in the C3 attack
 * set, not in configuration review.
 */
public final class OidcPkceAuthorizationRequestResolver
        implements ServerOAuth2AuthorizationRequestResolver {

    private final ServerOAuth2AuthorizationRequestResolver delegate;

    public OidcPkceAuthorizationRequestResolver(
            ReactiveClientRegistrationRepository clientRegistrations) {
        DefaultServerOAuth2AuthorizationRequestResolver defaults =
                new DefaultServerOAuth2AuthorizationRequestResolver(
                        Objects.requireNonNull(clientRegistrations, "clientRegistrations"));
        defaults.setAuthorizationRequestCustomizer(
                OAuth2AuthorizationRequestCustomizers.withPkce());
        this.delegate = defaults;
    }

    @Override
    public Mono<OAuth2AuthorizationRequest> resolve(ServerWebExchange exchange) {
        return delegate.resolve(exchange);
    }

    @Override
    public Mono<OAuth2AuthorizationRequest> resolve(ServerWebExchange exchange, String registrationId) {
        return delegate.resolve(exchange, registrationId);
    }
}
