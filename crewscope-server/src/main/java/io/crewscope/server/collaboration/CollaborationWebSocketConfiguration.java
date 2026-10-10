package io.crewscope.server.collaboration;

import io.crewscope.server.config.application.CollaborationRealtimeProperties;
import io.crewscope.server.security.AuthenticationSubjectExtractor;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.security.web.server.context.WebSessionServerSecurityContextRepository;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.server.RequestUpgradeStrategy;
import org.springframework.web.reactive.socket.server.support.HandshakeWebSocketService;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;
import org.springframework.web.reactive.socket.server.upgrade.ReactorNettyRequestUpgradeStrategy;
import reactor.netty.http.server.WebsocketServerSpec;

/**
 * WebSocket transport wiring (M11-I01a). The endpoint lives under /api/v1 so the existing
 * security chain (authentication) and SameOriginWebFilter (CSWSH protection) cover the upgrade
 * request with zero filter changes. Everything here registers only when the channel is enabled;
 * a disabled deployment exposes no endpoint at all.
 *
 * <p>Spring Framework 7 copies WebSession attributes into the WS handshake only for keys the
 * sessionAttributePredicate accepts (an empty map by default). The predicate below whitelists
 * exactly the security context — the mechanism ADR-032 freezes for the product — so the handler
 * reads the same session identity the HTTP endpoints see.</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    prefix = "crewscope.collaboration-realtime",
    name = "enabled",
    havingValue = "true")
@EnableConfigurationProperties(CollaborationRealtimeProperties.class)
public class CollaborationWebSocketConfiguration {

  static final String WS_PATH = "/api/v1/collaboration/ws";

  @Bean
  CollaborationConnectionRegistry collaborationConnectionRegistry(
      CollaborationRealtimeProperties properties) {
    return new CollaborationConnectionRegistry(
        properties.getMaxConnectionsPerPrincipal(),
        properties.getSoftConnectionBudget(),
        properties.getHardConnectionLimit());
  }

  @Bean
  CollaborationConnectionMetrics collaborationConnectionMetrics(
      MeterRegistry meterRegistry, CollaborationConnectionRegistry registry) {
    return new CollaborationConnectionMetrics(meterRegistry, registry);
  }

  @Bean
  CollaborationSubscriptionRegistry collaborationSubscriptionRegistry(
      CollaborationRealtimeProperties properties) {
    return new CollaborationSubscriptionRegistry(
        properties.getMaxSubscriptionsPerConnection());
  }

  @Bean
  CollaborationPresenceKeyspace collaborationPresenceKeyspace(
      CollaborationRealtimeProperties properties) {
    return new CollaborationPresenceKeyspace(properties.getEnvironment());
  }

  @Bean
  CollaborationPresenceStore collaborationPresenceStore(
      ReactiveStringRedisTemplate redisTemplate,
      CollaborationPresenceKeyspace keyspace,
      CollaborationRealtimeProperties properties) {
    return new CollaborationPresenceStore(
        redisTemplate, keyspace, properties.getPresenceTtl(), Clock.systemUTC());
  }

  @Bean
  CollaborationPresenceSweeper collaborationPresenceSweeper(
      ReactiveStringRedisTemplate redisTemplate,
      CollaborationPresenceKeyspace keyspace,
      CollaborationRealtimeProperties properties) {
    return new CollaborationPresenceSweeper(
        redisTemplate, keyspace, properties.getPresenceSweepInterval(), Clock.systemUTC());
  }

  @Bean
  CollaborationRevocationRevalidator collaborationRevocationRevalidator(
      CollaborationSubscriptionAuthorizer subscriptionAuthorizer,
      CollaborationRealtimeProperties properties) {
    return new CollaborationRevocationRevalidator(
        subscriptionAuthorizer, properties.getRevalidationInterval(), Clock.systemUTC());
  }

  /**
   * The in-memory {@link io.crewscope.application.collaboration.CollaborationSignalSink}: the
   * outbox consumer (assembled by the signal configuration under the same switch) pushes change
   * facts here while the handler registers live connections into it.
   */
  @Bean
  CollaborationSignalFanout collaborationSignalFanout(
      CollaborationSubscriptionRegistry subscriptions,
      CollaborationPresenceKeyspace keyspace,
      CollaborationPresenceStore presenceStore,
      CollaborationConnectionMetrics metrics,
      CollaborationRevocationRevalidator revalidator,
      CollaborationRealtimeProperties properties) {
    return new CollaborationSignalFanout(
        subscriptions,
        keyspace,
        presenceStore,
        metrics,
        revalidator,
        properties.getTypingWindow(),
        Clock.systemUTC());
  }

  @Bean
  CollaborationConnectionHandler collaborationConnectionHandler(
      CollaborationRealtimeProperties properties,
      CollaborationConnectionRegistry registry,
      CollaborationConnectionMetrics metrics,
      AuthenticationSubjectExtractor subjectExtractor,
      CollaborationSubscriptionRegistry subscriptions,
      CollaborationSubscriptionAuthorizer subscriptionAuthorizer,
      CollaborationPresenceKeyspace presenceKeyspace,
      CollaborationPresenceStore presenceStore,
      CollaborationSignalFanout signalFanout,
      CollaborationDisplayNameResolver displayNameResolver) {
    return new CollaborationConnectionHandler(
        registry,
        metrics,
        subjectExtractor,
        subscriptions,
        subscriptionAuthorizer,
        presenceKeyspace,
        presenceStore,
        signalFanout,
        displayNameResolver,
        properties.getHeartbeatInterval(),
        properties.getInboundIdleTimeout(),
        properties.getPresenceTtl(),
        properties.getOutboundFrameBufferLimit(),
        properties.getMaxInboundSignalsPerSecond(),
        properties.getTypingWindow());
  }

  @Bean
  HandlerMapping collaborationHandlerMapping(CollaborationConnectionHandler handler) {
    return new SimpleUrlHandlerMapping(Map.of(WS_PATH, (WebSocketHandler) handler), -1);
  }

  @Bean
  WebSocketHandlerAdapter collaborationWebSocketHandlerAdapter(
      CollaborationRealtimeProperties properties) {
    RequestUpgradeStrategy upgradeStrategy =
        new ReactorNettyRequestUpgradeStrategy(
            () ->
                WebsocketServerSpec.builder()
                    .maxFramePayloadLength(
                        (int) properties.getMaxInboundFrameSize().toBytes()));
    HandshakeWebSocketService service = new HandshakeWebSocketService(upgradeStrategy);
    service.setSessionAttributePredicate(
        WebSessionServerSecurityContextRepository.DEFAULT_SPRING_SECURITY_CONTEXT_ATTR_NAME
            ::equals);
    return new WebSocketHandlerAdapter(service);
  }
}
