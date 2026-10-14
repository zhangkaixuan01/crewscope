package io.crewscope.server.security.oidc;

import io.crewscope.application.identity.AuthenticationFlow;
import io.crewscope.application.identity.LoginDefense;
import io.crewscope.server.security.ApiSecurityResponseWriter;
import io.crewscope.server.security.login.ControlledNetworkSourceResolver;
import java.util.Objects;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Consumes the controlled-network window of the login defense before the authorization redirect
 * (M12-I01): the OIDC start carries no user-submitted identifier, so only the network dimension
 * applies. Rate-limited starts fail closed with 429; a defense store that cannot answer fails
 * closed with 503 — an unreachable limiter must never widen into an open one.
 *
 * <p>Registered as a plain WebFilter ordered ahead of the Spring Security chain proxy: the
 * framework's authorization-redirect filter short-circuits {@code GET /oauth2/authorization/*}
 * inside that chain, so a gate mounted behind it would never see a single start.
 */
public final class OidcAuthorizationStartDefenseWebFilter implements WebFilter, Ordered {

    private static final ServerWebExchangeMatcher AUTHORIZATION_START =
            ServerWebExchangeMatchers.pathMatchers(HttpMethod.GET, "/oauth2/authorization/*");

    private enum StartAdmission {
        ALLOWED,
        RATE_LIMITED,
        UNAVAILABLE
    }

    private final LoginDefense defense;
    private final ControlledNetworkSourceResolver networkSources;
    private final ApiSecurityResponseWriter responses;

    public OidcAuthorizationStartDefenseWebFilter(
            LoginDefense defense,
            ControlledNetworkSourceResolver networkSources,
            ApiSecurityResponseWriter responses) {
        this.defense = Objects.requireNonNull(defense, "defense");
        this.networkSources = Objects.requireNonNull(networkSources, "networkSources");
        this.responses = Objects.requireNonNull(responses, "responses");
    }

    /** Ahead of the security chain proxy (order -100) and behind the test error printer. */
    @Override
    public int getOrder() {
        return -200;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        return AUTHORIZATION_START
                .matches(exchange)
                .flatMap(ignored -> admission(exchange))
                .flatMap(
                        start -> switch (start) {
                            case ALLOWED -> chain.filter(exchange);
                            case RATE_LIMITED -> responses.loginDefenseRateLimited(exchange);
                            case UNAVAILABLE -> responses.loginDefenseUnavailable(exchange);
                        })
                .switchIfEmpty(Mono.defer(() -> chain.filter(exchange)));
    }

    private Mono<StartAdmission> admission(ServerWebExchange exchange) {
        return Mono.fromCompletionStage(
                        defense.admitNetwork(
                                AuthenticationFlow.OIDC, networkSources.resolve(exchange)))
                .map(
                        admission ->
                                admission.allowed()
                                        ? StartAdmission.ALLOWED
                                        : StartAdmission.RATE_LIMITED)
                .onErrorResume(ignored -> Mono.just(StartAdmission.UNAVAILABLE));
    }
}
