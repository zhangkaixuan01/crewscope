package io.crewscope.agentscope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.crewscope.domain.conversation.AgentRuntimeSession;
import io.crewscope.domain.workspace.AgentProfileId;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PersonalAgentFactoryTest {

    @TempDir Path runtimeRoot;

    @Test
    void reusesOneHarnessAgentForTheSamePinnedProfileVersion() {
        AgentProfileId profileId = AgentProfileId.generate();
        ScriptedModel model = new ScriptedModel("unused");
        PersonalAgentFactory factory = factory(profileId, model);
        AgentRuntimeSession firstSession = AgentScopeRuntimeTestFixture.session(profileId, 3);
        AgentRuntimeSession secondSession = AgentScopeRuntimeTestFixture.session(profileId, 3);

        HarnessAgent first = factory.getOrCreate(firstSession);
        HarnessAgent second = factory.getOrCreate(secondSession);

        assertSame(first, second);
        assertEquals(1, factory.cachedAgentCount());
        ObservableAgentScopeModel observed = (ObservableAgentScopeModel) first.getModel();
        assertSame(model, observed.delegate());
        assertEquals(AgentModelRole.PRIMARY, observed.role());
        assertEquals(6, first.getMaxIters());
        factory.close();
    }

    @Test
    void createsAnIndependentHarnessAgentAfterProfileVersionAdvances() {
        AgentProfileId profileId = AgentProfileId.generate();
        PersonalAgentFactory factory = factory(profileId, new ScriptedModel("unused"));

        HarnessAgent versionThree = factory.getOrCreate(
                AgentScopeRuntimeTestFixture.session(profileId, 3));
        HarnessAgent versionFour = factory.getOrCreate(
                AgentScopeRuntimeTestFixture.session(profileId, 4));

        assertNotSame(versionThree, versionFour);
        assertEquals(2, factory.cachedAgentCount());
        factory.close();
    }

    @Test
    void rejectsConfigurationThatDoesNotMatchThePinnedProfileVersion() {
        AgentProfileId profileId = AgentProfileId.generate();
        AgentProfileId foreignProfileId = AgentProfileId.generate();
        PersonalAgentFactory factory = new PersonalAgentFactory(
                (ignoredId, ignoredVersion) -> configuration(foreignProfileId, 3),
                ignored -> new ScriptedModel("unused"),
                new InMemoryAgentStateStore(),
                Toolkit::new,
                runtimeRoot);

        assertThrows(
                IllegalStateException.class,
                () -> factory.getOrCreate(AgentScopeRuntimeTestFixture.session(profileId, 3)));
        assertEquals(0, factory.cachedAgentCount());
        factory.close();
    }

    @Test
    void validatesConfigurationAndRejectsCreationAfterClose() {
        AgentProfileId profileId = AgentProfileId.generate();
        assertThrows(
                IllegalArgumentException.class,
                () -> new AgentScopePersonalAgentConfiguration(
                        profileId,
                        0,
                        "primary",
                        Optional.of("primary"),
                        "prompt",
                        5,
                        1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AgentScopePersonalAgentConfiguration(
                        profileId,
                        0,
                        "primary",
                        Optional.empty(),
                        "prompt",
                        0,
                        1));

        PersonalAgentFactory factory = factory(profileId, new ScriptedModel("unused"));
        factory.close();
        assertThrows(
                IllegalStateException.class,
                () -> factory.getOrCreate(AgentScopeRuntimeTestFixture.session(profileId, 3)));
    }

    private PersonalAgentFactory factory(AgentProfileId profileId, ScriptedModel model) {
        return new PersonalAgentFactory(
                (requestedId, version) -> {
                    assertEquals(profileId, requestedId);
                    return configuration(requestedId, version);
                },
                modelId -> model,
                new InMemoryAgentStateStore(),
                Toolkit::new,
                runtimeRoot);
    }

    private static AgentScopePersonalAgentConfiguration configuration(
            AgentProfileId profileId, long version) {
        return new AgentScopePersonalAgentConfiguration(
                profileId,
                version,
                "primary-model",
                Optional.empty(),
                "You are the CrewScope Personal Agent.",
                6,
                2);
    }

}
