package io.crewscope.probe.auth;

import io.crewscope.probe.presence.PresenceStore;
import io.crewscope.probe.ws.CollaborationProbeHandler;
import io.crewscope.probe.ws.ProbeConnection;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * The two revocation layers of ADR-038 section 3.1, exercised separately:
 *
 * <ul>
 *   <li>layer1 — the event-consumer path: close every live connection of the
 *       (team, principal) with code 4403 and delete the presence keys
 *       immediately;</li>
 *   <li>layer2 — the revalidation path only: membership flips but nothing is
 *       closed; the next frame emitted after the 5s cache expires closes the
 *       connection. The load scenario measures that window.</li>
 * </ul>
 *
 * In the product the membership flip itself is an M9b command through
 * TeamMemberLifecycleApplicationService; this endpoint only simulates the
 * channel-side effect.
 */
@RestController
public class RevocationController {

    private final MembershipSimulator membership;
    private final io.crewscope.probe.ws.ConnectionRegistry registry;
    private final PresenceStore presence;

    public RevocationController(MembershipSimulator membership,
            io.crewscope.probe.ws.ConnectionRegistry registry, PresenceStore presence) {
        this.membership = membership;
        this.registry = registry;
        this.presence = presence;
    }

    @PostMapping("/probe/admin/teams/{teamId}/principals/{principalId}/revoke")
    public Mono<Map<String, Object>> revoke(@PathVariable String teamId,
            @PathVariable String principalId, @RequestParam(defaultValue = "layer1") String mode) {
        membership.revoke(teamId, principalId);
        List<ProbeConnection> connections = registry.findByPrincipal(teamId, principalId);
        long closed;
        if ("layer1".equals(mode)) {
            closed = connections.size();
            for (ProbeConnection connection : connections) {
                presence.remove(connection.id(), connection.presenceScope()).subscribe();
                if (connection.tryClose()) {
                    connection.session()
                            .close(CollaborationProbeHandler.CLOSE_REVOKED)
                            .subscribe();
                }
            }
        } else {
            closed = 0;
        }
        return Mono.just(Map.of(
                "mode", mode,
                "matchedConnections", connections.size(),
                "closedNow", closed,
                "expectedEffect", "layer1".equals(mode)
                        ? "closed with 4403 and presence removed"
                        : "closed by revalidation on the next frame after the cache expires"));
    }
}
