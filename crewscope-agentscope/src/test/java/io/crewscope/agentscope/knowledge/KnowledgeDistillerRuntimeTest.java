package io.crewscope.agentscope.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.HarnessAgent;
import io.crewscope.agentscope.PlatformAgentMiddlewareSet;
import io.crewscope.agentscope.SafeModelExecutionException;
import io.crewscope.agentscope.template.AgentTemplateRuntimeDefinition;
import io.crewscope.agentscope.template.AgentTemplateRuntimeRegistry;
import io.crewscope.agentscope.template.RestrictedTemplateAgentBuilder;
import io.crewscope.agentscope.template.TemplateAgentRuntimeFactory;
import io.crewscope.agentscope.template.TemplateSpecialistAgentFactory;
import io.crewscope.domain.agent.AgentConfigurationVersion;
import io.crewscope.domain.agent.AgentExecutionModelBinding;
import io.crewscope.domain.agent.AgentExecutionScope;
import io.crewscope.domain.agent.AgentRuntimeRole;
import io.crewscope.domain.agent.AgentTemplateDefinition;
import io.crewscope.domain.agent.ResolvedAgentExecutionConfiguration;
import io.crewscope.domain.agent.ResolvedModelSelection;
import io.crewscope.domain.agent.SafeModelGenerateOptions;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.knowledge.distiller.KnowledgeDistillerInitialization;
import io.crewscope.domain.knowledge.distiller.KnowledgeDistillerTemplate;
import io.crewscope.domain.model.ModelConnectionOwner;
import io.crewscope.domain.policy.PolicyPackId;
import io.crewscope.domain.policy.PolicyPackReference;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.team.TeamInitialization;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** M10-A02b Distiller runtime: real harness loop, decode failures, timeouts and bounds. */
class KnowledgeDistillerRuntimeTest {

    @TempDir
    Path runtimeRoot;

    @Test
    void executesTheRealAgentScopeHarnessAndCapturesOneUsageFactPerAttempt() {
        Fixture fixture = new Fixture();
        fixture.useModel(new LoopbackDistillerModel());
        PlatformAgentMiddlewareSet middlewareSet = mock(PlatformAgentMiddlewareSet.class);
        when(middlewareSet.ordered()).thenReturn(List.of());
        TemplateSpecialistAgentFactory specialist = new TemplateSpecialistAgentFactory(
                new RestrictedTemplateAgentBuilder(
                        new InMemoryAgentStateStore(), runtimeRoot, 4, middlewareSet),
                Optional.empty());
        AgentTemplateRuntimeRegistry registry = new AgentTemplateRuntimeRegistry(List.of(
                factory(AgentRuntimeRole.PERSONAL_ASSISTANT),
                factory(AgentRuntimeRole.TEAM_COORDINATOR),
                specialist));
        KnowledgeDistillerRuntime runtime = new KnowledgeDistillerRuntime(
                registry, fixture.templates, Duration.ofSeconds(30));

        KnowledgeDistillerRuntime.Observation observed =
                runtime.distill(fixture.request()).block();

        assertEquals("Postmortem: cache eviction storms", observed.draft().title());
        assertEquals("Increase eviction jitter and add a soak window.",
                observed.draft().content());
        assertEquals("DECISION", observed.draft().suggestedCategory());
        assertEquals(Optional.of("a02b-loopback"), observed.observedModelName());
        assertEquals(1, observed.attempts().size());
        assertEquals(1, observed.attempts().get(0).attempt());
        assertEquals(20, observed.attempts().get(0).usage().inputTokens());
        assertEquals(5, observed.attempts().get(0).usage().outputTokens());
        assertEquals(0, observed.attempts().get(0).usage().cachedTokens());
        assertEquals(25, observed.attempts().get(0).usage().totalTokens());
    }

    @Test
    void failsWhenTheModelReturnsNoStructuredData() {
        Fixture fixture = new Fixture();
        Msg bare = mock(Msg.class);
        when(bare.hasStructuredData()).thenReturn(false);
        when(fixture.agent.call(anyList(), any(JsonNode.class), any(RuntimeContext.class)))
                .thenReturn(Mono.just(bare));
        KnowledgeDistillerRuntime runtime = fixture.runtime();

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> runtime.distill(fixture.request()).block());

        assertTrue(failure.getMessage().contains("structured output"), failure.getMessage());
    }

    @Test
    void rejectsStructuredOutputThatDriftsFromTheFrozenSchema() {
        Fixture fixture = new Fixture();
        Map<String, Object> drift = new LinkedHashMap<>();
        drift.put("title", "Missing the required content and category");
        when(fixture.message.getStructuredData(false)).thenReturn(drift);
        KnowledgeDistillerRuntime runtime = fixture.runtime();

        assertThrows(
                IllegalArgumentException.class,
                () -> runtime.distill(fixture.request()).block());
    }

    @Test
    void sanitizesTimeoutFailuresToTheProviderNeutralCode() {
        Fixture fixture = new Fixture();
        when(fixture.agent.call(anyList(), any(JsonNode.class), any(RuntimeContext.class)))
                .thenReturn(Mono.never());
        KnowledgeDistillerRuntime runtime = new KnowledgeDistillerRuntime(
                request -> fixture.agent,
                fixture.templates,
                Duration.ofMillis(50));

        Throwable failure = assertThrows(
                Exception.class, () -> runtime.distill(fixture.request()).block());

        SafeModelExecutionException sanitized =
                assertInstanceOf(SafeModelExecutionException.class, failure);
        assertEquals("MODEL_TIMEOUT", sanitized.safeCode());
    }

    @Test
    void rejectsTranscriptsBeyondTheFrozenSourceBound() {
        Fixture fixture = new Fixture();

        assertThrows(IllegalArgumentException.class, () -> fixture.request("a".repeat(131_073)));
        assertThrows(IllegalArgumentException.class, () -> fixture.request(" "));
        assertThrows(IllegalArgumentException.class, () -> new KnowledgeDistillerRuntimeRequest(
                fixture.definition, fixture.session, TaskExecutionId.generate(), 1,
                fixture.transcript(), ""));
    }

    private static TemplateAgentRuntimeFactory factory(AgentRuntimeRole role) {
        TemplateAgentRuntimeFactory factory = mock(TemplateAgentRuntimeFactory.class);
        when(factory.runtimeRole()).thenReturn(role);
        return factory;
    }

    /** Single-turn loopback: answer the structured-output tool call immediately. */
    private static final class LoopbackDistillerModel implements Model {

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            Map<String, Object> draft = Map.of(
                    "title", "Postmortem: cache eviction storms",
                    "content", "Increase eviction jitter and add a soak window.",
                    "suggestedCategory", "DECISION");
            Map<String, Object> input = Map.of("response", draft);
            return Flux.just(ChatResponse.builder()
                    .content(List.of(ToolUseBlock.builder()
                            .id("distilled-draft")
                            .name("generate_response")
                            .input(input)
                            .content(JsonUtils.getJsonCodec().toJson(input))
                            .build()))
                    .usage(new ChatUsage(20, 5, 0.002))
                    .build());
        }

        @Override
        public String getModelName() {
            return "a02b-loopback";
        }
    }

    private static final class Fixture {
        private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-10-02T05:00:00Z");

        private final OrganizationId organizationId = OrganizationId.generate();
        private final Principal owner;
        private final TeamInitialization team;
        private final AgentTemplateDefinition template;
        private final AgentConfigurationVersion configuration;
        private final KnowledgeDistillerInitialization distiller;
        private final ResolvedModelSelection primary = mock(ResolvedModelSelection.class);
        private final ResolvedAgentExecutionConfiguration resolved =
                mock(ResolvedAgentExecutionConfiguration.class);
        private final AgentTemplateRuntimeDefinition definition =
                mock(AgentTemplateRuntimeDefinition.class);
        private final KnowledgeDistillerTemplateRuntimeRegistry templates =
                new KnowledgeDistillerTemplateRuntimeRegistry();
        private final KnowledgeDistillerRuntimeSession session;
        private final HarnessAgent agent = mock(HarnessAgent.class);
        private final Msg message = mock(Msg.class);

        private Fixture() {
            owner = Principal.create(
                    PrincipalId.generate(),
                    PrincipalScope.organization(organizationId),
                    PrincipalType.USER,
                    Optional.empty(),
                    "Owner",
                    Optional.empty(),
                    PrincipalVisibility.ORGANIZATION,
                    NOW);
            team = TeamInitialization.create(owner, "Knowledge", NOW);
            template = KnowledgeDistillerTemplate.create(organizationId, owner.id(), NOW);
            KnowledgeDistillerInitialization disabled = KnowledgeDistillerInitialization
                    .createDefault(
                            team.team(), team.defaultWorkspace(), team.ownerMember(), owner,
                            template, NOW);
            configuration = AgentConfigurationVersion.createInitial(
                    disabled.agentProfile(),
                    template,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(AgentExecutionModelBinding.inheritTeamDefault()),
                    Optional.empty(),
                    Set.of(),
                    Optional.empty(),
                    Optional.empty(),
                    new PolicyPackReference(PolicyPackId.generate(), 1),
                    SafeModelGenerateOptions.defaults(),
                    owner.id(),
                    NOW);
            distiller = disabled.activate(configuration, owner.id(), NOW);
            session = new KnowledgeDistillerRuntimeSession(
                    organizationId,
                    team.team().id(),
                    owner.id(),
                    distiller.agentPrincipal().id(),
                    distiller.agentProfile().id(),
                    distiller.agentProfile().version(),
                    UUID.randomUUID());

            when(primary.connectionOwner())
                    .thenReturn(ModelConnectionOwner.organization(organizationId));
            when(resolved.executionScope()).thenReturn(AgentExecutionScope.TEAM);
            when(resolved.agentProfileId()).thenReturn(distiller.agentProfile().id());
            when(resolved.agentProfileVersion()).thenReturn(distiller.agentProfile().version());
            when(resolved.agentPrincipalId()).thenReturn(distiller.agentPrincipal().id());
            when(resolved.ownership()).thenReturn(distiller.agentProfile().ownership());
            when(resolved.templateVersion()).thenReturn(template.templateVersion());
            when(resolved.templateContentHash()).thenReturn(template.contentHash());
            when(resolved.configurationRevision()).thenReturn(configuration.revision());
            when(resolved.configurationHash()).thenReturn(configuration.configurationHash());
            when(resolved.structuredOutputSchemaHash()).thenReturn(
                    template.policy().structuredOutputSchemaHash());
            when(resolved.primary()).thenReturn(primary);
            when(resolved.fallback()).thenReturn(Optional.empty());
            when(definition.profile()).thenReturn(distiller.agentProfile());
            when(definition.template()).thenReturn(template);
            when(definition.configuration()).thenReturn(configuration);
            when(definition.resolved()).thenReturn(resolved);
            when(definition.enabledToolNames()).thenReturn(Set.of());
            when(definition.systemPrompt())
                    .thenReturn(template.policy().systemPromptBaseline());
            when(definition.fallbackModel()).thenReturn(Optional.empty());
            when(message.hasStructuredData()).thenReturn(true);
            when(message.getStructuredData(false)).thenReturn(Map.of(
                    "title", "Postmortem: cache eviction storms",
                    "content", "Increase eviction jitter and add a soak window.",
                    "suggestedCategory", "DECISION"));
            when(agent.call(anyList(), any(JsonNode.class), any(RuntimeContext.class)))
                    .thenReturn(Mono.just(message));
        }

        private void useModel(Model model) {
            when(definition.primaryModel()).thenReturn(model);
        }

        private KnowledgeDistillerRuntime runtime() {
            return new KnowledgeDistillerRuntime(
                    request -> agent, templates, Duration.ofSeconds(5));
        }

        private String transcript() {
            return "COMPLETED Build finished; LOG TextDelta 'cache eviction storm resolved'";
        }

        private KnowledgeDistillerRuntimeRequest request() {
            return request(transcript());
        }

        private KnowledgeDistillerRuntimeRequest request(String transcript) {
            return new KnowledgeDistillerRuntimeRequest(
                    definition,
                    session,
                    TaskExecutionId.generate(),
                    1,
                    transcript,
                    "LET_THE_MODEL_SUGGEST");
        }
    }
}
