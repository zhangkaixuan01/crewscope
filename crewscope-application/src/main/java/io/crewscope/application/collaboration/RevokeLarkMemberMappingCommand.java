package io.crewscope.application.collaboration;

import io.crewscope.domain.collaboration.LarkMemberMappingId;
import io.crewscope.domain.collaboration.LarkMemberMappingTerminalReason;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.id.OrganizationId;
import java.util.Objects;

/** Strong-versioned administrator revocation of one confirmed mapping. */
public record RevokeLarkMemberMappingCommand(
        OrganizationId organizationId,
        LarkMemberMappingId mappingId,
        long expectedVersion,
        LarkMemberMappingTerminalReason reason,
        Principal actor) {

    public RevokeLarkMemberMappingCommand {
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        mappingId = Objects.requireNonNull(mappingId, "mappingId");
        actor = Objects.requireNonNull(actor, "actor");
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must not be negative");
        }
        reason = Objects.requireNonNull(reason, "reason");
        if (!reason.supports(io.crewscope.domain.collaboration.LarkMemberMappingStatus.REVOKED)) {
            throw new IllegalArgumentException("reason must support REVOKED mapping status");
        }
    }
}
