package io.crewscope.probe.config;

import io.crewscope.probe.auth.PrincipalContext;
import io.crewscope.probe.ws.CollaborationProbeHandler;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.server.RequestUpgradeStrategy;
import org.springframework.web.reactive.socket.server.support.HandshakeWebSocketService;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;
import org.springframework.web.reactive.socket.server.upgrade.ReactorNettyRequestUpgradeStrategy;

/**
 * WebFlux WebSocket wiring. There is no HandshakeInterceptor in the reactive
 * stack: the upgrade request is a plain HTTP request that traverses the
 * WebFilter chain first (OriginCheckWebFilter, then SessionAuthWebFilter,
 * which rejects unauthenticated upgrades with 401), and the handler reads
 * the principal from {@code WebSocketSession.getAttributes()}.
 *
 * <p>Spring Framework 7 changed what that map contains: session attributes
 * are copied in only for keys accepted by the {@code sessionAttributePredicate}
 * (empty map by default — the 6.x exchange attributes are gone). The
 * predicate below whitelists exactly the principal attribute and nothing
 * else, which is the mechanism ADR-032 freezes for the product.</p>
 */
@Configuration
public class WebSocketConfiguration {

    @Bean
    public HandlerMapping probeHandlerMapping(CollaborationProbeHandler handler) {
        return new SimpleUrlHandlerMapping(Map.of("/ws/probe", (WebSocketHandler) handler), -1);
    }

    @Bean
    public WebSocketHandlerAdapter probeWebSocketHandlerAdapter() {
        RequestUpgradeStrategy upgradeStrategy = new ReactorNettyRequestUpgradeStrategy();
        HandshakeWebSocketService service = new HandshakeWebSocketService(upgradeStrategy);
        service.setSessionAttributePredicate(PrincipalContext.SESSION_ATTRIBUTE::equals);
        return new WebSocketHandlerAdapter(service);
    }
}
