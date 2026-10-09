package io.crewscope.probe.auth;

import java.net.URI;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Cross-site WebSocket hijacking guard for the upgrade request, mirroring the
 * product's SameOriginWebFilter position (before authentication). A request
 * that presents an Origin header must present the same host as the request
 * itself; clients that send no Origin at all (curl, the load generator's raw
 * upgrade path) are allowed through so the negative case can be exercised.
 */
@Component
@Order(-200)
public class OriginCheckWebFilter implements WebFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (!"/ws/probe".equals(path)) {
            return chain.filter(exchange);
        }
        String origin = exchange.getRequest().getHeaders().getFirst("Origin");
        if (origin == null) {
            return chain.filter(exchange);
        }
        String originHost = URI.create(origin).getAuthority();
        String requestHost = exchange.getRequest().getHeaders().getFirst("Host");
        if (originHost != null && originHost.equalsIgnoreCase(requestHost)) {
            return chain.filter(exchange);
        }
        exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
        return exchange.getResponse().setComplete();
    }
}
