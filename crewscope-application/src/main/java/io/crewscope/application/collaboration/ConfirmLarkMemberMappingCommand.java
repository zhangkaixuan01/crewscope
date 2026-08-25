package io.crewscope.application.collaboration;

import io.crewscope.domain.collaboration.LarkMemberVerificationProofId;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.provider.ProviderBindingId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.team.TeamMemberId;
import java.util.Objects;

/** Team-admin confirmation of one fresh exact verification proof. */
public record ConfirmLarkMemberMappingCommand(
        OrganizationId organizationId,
        TeamId teamId,
        TeamMemberId memberId,
        ProviderBindingId providerBindingId,
        LarkMemberVerificationProofId proofId,
        Principal actor) {

    public ConfirmLarkMemberMappingCommand {
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        teamId = Objects.requireNonNull(teamId, "teamId");
        memberId = Objects.requireNonNull(memberId, "memberId");
        providerBindingId = Objects.requireNonNull(providerBindingId, "providerBindingId");
        proofId = Objects.requireNonNull(proofId, "proofId");
        actor = Objects.requireNonNull(actor, "actor");
    }
}
