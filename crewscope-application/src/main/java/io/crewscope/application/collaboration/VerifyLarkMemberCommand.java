package io.crewscope.application.collaboration;

import io.crewscope.domain.collaboration.LarkOpenId;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.provider.ProviderBindingId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import java.util.Objects;

/** Team-admin request for an exact open_id verification; fuzzy fields are absent. */
public record VerifyLarkMemberCommand(
        OrganizationId organizationId,
        TeamId teamId,
        ProviderBindingId providerBindingId,
        LarkOpenId openId,
        Principal actor) {

    public VerifyLarkMemberCommand {
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        teamId = Objects.requireNonNull(teamId, "teamId");
        providerBindingId = Objects.requireNonNull(providerBindingId, "providerBindingId");
        openId = Objects.requireNonNull(openId, "openId");
        actor = Objects.requireNonNull(actor, "actor");
    }
}
