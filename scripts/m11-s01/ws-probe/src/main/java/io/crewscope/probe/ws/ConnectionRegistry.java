package io.crewscope.probe.ws;

import io.crewscope.probe.auth.PrincipalContext;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Sinks;

/**
 * In-process connection registry: connection id -> state, plus the
 * per-(team, principal) reverse index the revocation event consumer uses.
 * Single-instance by design (ADR-038 section 3.1: the multi-instance control
 * channel is a registered re-evaluation trigger, not implemented here).
 */
@Component
public class ConnectionRegistry {

    private final Map<String, ProbeConnection> byId = new ConcurrentHashMap<>();
    private final AtomicLong framesIn = new AtomicLong();
    private final AtomicLong framesOut = new AtomicLong();
    private final AtomicLong deniedSubscriptions = new AtomicLong();
    private final io.crewscope.probe.config.ProbeProperties properties;

    public ConnectionRegistry(io.crewscope.probe.config.ProbeProperties properties) {
        this.properties = properties;
    }

    public ProbeConnection register(WebSocketSession session, PrincipalContext principal) {
        String id = session.getId();
        // Bounded buffer: a client that stops draining its socket lets the
        // buffer fill and the emit path closes it (slow-client handling).
        Sinks.Many<String> outbound = Sinks.many().unicast().onBackpressureBuffer(
                new java.util.concurrent.ArrayBlockingQueue<String>(properties.outboundBuffer()));
        ProbeConnection connection = new ProbeConnection(id, session, principal, outbound);
        byId.put(id, connection);
        return connection;
    }

    public void unregister(ProbeConnection connection) {
        byId.remove(connection.id());
    }

    public Collection<ProbeConnection> connections() {
        return byId.values();
    }

    public int connectionCount() {
        return byId.size();
    }

    public List<ProbeConnection> findByPrincipal(String teamId, String principalId) {
        return byId.values().stream()
                .filter(c -> c.principal().teamId().equals(teamId)
                        && c.principal().principalId().equals(principalId))
                .toList();
    }

    public AtomicLong framesIn() {
        return framesIn;
    }

    public AtomicLong framesOut() {
        return framesOut;
    }

    public AtomicLong deniedSubscriptions() {
        return deniedSubscriptions;
    }
}
