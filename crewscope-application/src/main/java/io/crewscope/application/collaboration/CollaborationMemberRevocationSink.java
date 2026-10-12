package io.crewscope.application.collaboration;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import java.util.UUID;

/**
 * Outbound port for the event-driven revocation sweep (M11-D02, ADR-038 §3.1 layer 1).
 * The outbox consumer maps member lifecycle events onto this port; the transport (the
 * WebSocket fan-out in the server module) implements it by closing every connection of
 * that member in that Team with 4403 and cleaning the Redis presence keys.
 *
 * <p>Implementations are invoked from inside the outbox receipt transaction on the outbox
 * dispatcher's fixed pool, so a conforming implementation must be non-blocking, must never
 * throw (a throw would roll back the receipt of every consumer of that event) and must be
 * idempotent — the dispatcher delivers at-least-once, and a repeated sweep must find
 * nothing left to close. The sweep is an accelerator, not the authority: the revalidation
 * layer and the presence TTL bound the same window when an event is lost.</p>
 */
public interface CollaborationMemberRevocationSink {

    /**
     * Cuts every live connection of the named member in the named Team (close 4403 plus
     * the idempotent topology and presence cleanup); never throws.
     */
    void memberAuthorizationRevoked(
        UUID userPrincipalId, OrganizationId organizationId, TeamId team);
}
