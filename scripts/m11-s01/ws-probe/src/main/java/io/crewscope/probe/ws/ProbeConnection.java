package io.crewscope.probe.ws;

import io.crewscope.probe.auth.PrincipalContext;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Sinks;

/**
 * Mutable per-connection state: the bounded outbound sink, the subscription
 * set (fanout topology), the scope the presence key currently lives in, and
 * the inbound liveness watermark the heartbeat checks against.
 */
public final class ProbeConnection {

    private final String id;
    private final WebSocketSession session;
    private final PrincipalContext principal;
    private final Sinks.Many<String> outbound;
    private final Set<String> subscriptions = ConcurrentHashMap.newKeySet();
    private final long connectedAt = System.currentTimeMillis();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile long lastInboundAt = System.currentTimeMillis();
    private volatile String presenceScopeKey;
    private volatile io.crewscope.probe.presence.PresenceStore.Scope presenceScope;

    ProbeConnection(String id, WebSocketSession session, PrincipalContext principal,
            Sinks.Many<String> outbound) {
        this.id = id;
        this.session = session;
        this.principal = principal;
        this.outbound = outbound;
    }

    public String id() {
        return id;
    }

    public WebSocketSession session() {
        return session;
    }

    public PrincipalContext principal() {
        return principal;
    }

    public Sinks.Many<String> outbound() {
        return outbound;
    }

    public Set<String> subscriptions() {
        return subscriptions;
    }

    public long connectedAt() {
        return connectedAt;
    }

    public long lastInboundAt() {
        return lastInboundAt;
    }

    public void markInbound() {
        this.lastInboundAt = System.currentTimeMillis();
    }

    public io.crewscope.probe.presence.PresenceStore.Scope presenceScope() {
        return presenceScope;
    }

    public void setPresenceScope(io.crewscope.probe.presence.PresenceStore.Scope scope) {
        this.presenceScope = scope;
        this.presenceScopeKey = scope == null ? null : scope.key();
    }

    public String presenceScopeKey() {
        return presenceScopeKey;
    }

    /** Claims the right to run the close/cleanup path exactly once. */
    public boolean tryClose() {
        return closed.compareAndSet(false, true);
    }
}
