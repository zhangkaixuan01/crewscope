package io.crewscope.probe.auth;

import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Login/logout for the probe's Redis-backed WebSession. Login is the only
 * unauthenticated write surface; it stores the PrincipalContext in the
 * session so the handshake filter and the admin endpoints can resolve it —
 * the cookie/session/attribute chain the product reuses for WebSocket.
 */
@RestController
public class ProbeLoginController {

    private final MembershipSimulator membership;

    public ProbeLoginController(MembershipSimulator membership) {
        this.membership = membership;
    }

    public record LoginRequest(String organizationId, String teamId,
            String principalId, String displayName) {}

    @PostMapping("/probe/login")
    public Mono<Map<String, Object>> login(@RequestBody LoginRequest request,
            org.springframework.web.server.ServerWebExchange exchange) {
        return exchange.getSession().map(session -> {
            PrincipalContext principal = new PrincipalContext(
                    request.organizationId(), request.teamId(),
                    request.principalId(), request.displayName());
            session.getAttributes().put(PrincipalContext.SESSION_ATTRIBUTE, principal);
            membership.activate(request.teamId(), request.principalId());
            return Map.<String, Object>of("ok", true, "principal", request.principalId());
        });
    }

    @PostMapping("/probe/logout")
    public Mono<Map<String, Object>> logout(
            org.springframework.web.server.ServerWebExchange exchange) {
        return exchange.getSession().flatMap(session -> {
            session.getAttributes().remove(PrincipalContext.SESSION_ATTRIBUTE);
            return session.invalidate().thenReturn(Map.<String, Object>of("ok", true));
        });
    }
}
