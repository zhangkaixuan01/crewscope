package io.crewscope.application.agent;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.workspace.AgentProfile;
import java.util.Objects;

/** Principal and AgentProfile that must be committed as one Agent lifecycle unit. */
public record AgentInstance(Principal principal, AgentProfile profile) {

    public AgentInstance {
        principal = Objects.requireNonNull(principal, "principal");
        profile = Objects.requireNonNull(profile, "profile");
        if (!principal.id().equals(profile.agentPrincipalId())
                || !principal.scope().organizationId().equals(profile.scope().organizationId())
                || !principal.scope().teamId().equals(profile.scope().teamId())) {
            throw new IllegalArgumentException(
                    "Agent Principal and AgentProfile must share exact identity and scope");
        }
    }
}
