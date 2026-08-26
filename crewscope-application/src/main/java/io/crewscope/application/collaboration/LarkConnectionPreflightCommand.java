package io.crewscope.application.collaboration;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.provider.ProviderBindingId;
import io.crewscope.domain.provider.ProviderCapabilities;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import java.util.Objects;

/** Administrator request to revalidate one exact Team Lark authorization graph. */
public record LarkConnectionPreflightCommand(
        OrganizationId organizationId,
        TeamId teamId,
        ProviderBindingId providerBindingId,
        ProviderCapabilities requiredCapabilities,
        Principal actor) {

    public LarkConnectionPreflightCommand {
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        teamId = Objects.requireNonNull(teamId, "teamId");
        providerBindingId = Objects.requireNonNull(providerBindingId, "providerBindingId");
        requiredCapabilities = Objects.requireNonNull(
                requiredCapabilities, "requiredCapabilities");
        actor = Objects.requireNonNull(actor, "actor");
    }
}
