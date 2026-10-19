package io.crewscope.domain.distiller;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.workspace.AgentProfile;
import java.util.Objects;

/** One synchronized Distiller Agent Principal/Profile pair produced by the contract. */
public record DistillerInitializationPair(Principal agentPrincipal, AgentProfile agentProfile) {

    public DistillerInitializationPair {
        agentPrincipal = Objects.requireNonNull(agentPrincipal, "agentPrincipal");
        agentProfile = Objects.requireNonNull(agentProfile, "agentProfile");
    }
}
