package io.crewscope.agentscope;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.model.Model;
import io.crewscope.agentscope.model.PolicySnapshotTaskModelSource;
import io.crewscope.agentscope.model.ResolvedAgentScopeModelFactory;
import io.crewscope.agentscope.model.ResolvedAgentScopeModels;
import io.crewscope.domain.agent.AgentOwnership;
import io.crewscope.domain.agent.ResolvedAgentExecutionConfiguration;
import io.crewscope.domain.agent.SafeModelGenerateOptions;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * M9b-Q02: the Task Worker's resolved-model source must delegate to the conversation runtime's
 * trusted factory with the pinned configuration's own organization and Agent Principal, so the
 * credential audit trail attributes Worker builds to the Agent that owns the configuration.
 */
class PolicySnapshotTaskModelSourceTest {

    @Test
    void delegatesWithThePinnedOrganizationAgentPrincipalAndDefaultOptions() {
        ResolvedAgentScopeModelFactory delegate = mock(ResolvedAgentScopeModelFactory.class);
        PolicySnapshotTaskModelSource source = new PolicySnapshotTaskModelSource(delegate);
        OrganizationId organizationId = OrganizationId.generate();
        PrincipalId agentPrincipalId = PrincipalId.generate();
        ResolvedAgentExecutionConfiguration resolved =
                mock(ResolvedAgentExecutionConfiguration.class);
        AgentOwnership ownership = mock(AgentOwnership.class);
        when(ownership.organizationId()).thenReturn(organizationId);
        when(resolved.ownership()).thenReturn(ownership);
        when(resolved.agentPrincipalId()).thenReturn(agentPrincipalId);
        ResolvedAgentScopeModels models = new ResolvedAgentScopeModels(
                mock(Model.class), Optional.empty());
        when(delegate.build(
                eq(organizationId), eq(resolved), eq(SafeModelGenerateOptions.defaults()),
                eq(agentPrincipalId), any(UUID.class)))
                .thenReturn(models);

        assertSame(models, source.build(resolved));

        verify(delegate).build(
                eq(organizationId), eq(resolved), eq(SafeModelGenerateOptions.defaults()),
                eq(agentPrincipalId), any(UUID.class));
    }
}
