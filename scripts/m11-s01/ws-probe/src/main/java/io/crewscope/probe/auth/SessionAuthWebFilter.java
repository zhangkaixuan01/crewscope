package io.crewscope.probe.auth;

import java.nio.charset.StandardCharsets;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Session-reused authentication for the protected surface: the WebSocket
 * upgrade path and the admin endpoints. The upgrade is a plain HTTP request,
 * so resolving the Redis-backed WebSession here and copying the principal
 * into the exchange attributes is the WebFlux equivalent of a handshake
 * interceptor — what ADR-032 freezes as "reuse the existing session, no
 * second token". Unauthenticated upgrades get a 401 and never reach the
 * WebSocket handler.
 */
@Component
@Order(-100)
public class SessionAuthWebFilter implements WebFilter {

    private static final String ADMIN_PREFIX = "/probe/admin";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        boolean protectedPath = "/ws/probe".equals(path) || path.startsWith(ADMIN_PREFIX);
        if (!protectedPath) {
            return chain.filter(exchange);
        }
        return exchange.getSession()
                .flatMap(session -> {
                    Object principal = session.getAttribute(PrincipalContext.SESSION_ATTRIBUTE);
                    if (principal instanceof PrincipalContext) {
                        // The handler re-reads the same session attribute via
                        // WebSocketSession.getAttributes() (the WebSession map,
                        // which Spring Framework 7 passes into HandshakeInfo).
                        return chain.filter(exchange);
                    }
                    return unauthorized(exchange);
                });
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body = "{\"error\":\"unauthorized\"}".getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = DefaultDataBufferFactory.sharedInstance.wrap(body);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }
}
