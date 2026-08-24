package io.crewscope.application.agent;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.model.ModelConnection;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.workspace.AgentProfile;
import java.util.List;

/** Server-owned Organization/Team policy source used by Agent configuration and Preflight APIs. */
@FunctionalInterface
public interface AgentModelGovernance {

    AgentModelGovernanceSnapshot resolve(
            Principal actor,
            TeamId teamId,
            AgentProfile profile,
            List<ModelConnection> usableConnections);
}
