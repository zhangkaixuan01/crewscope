package io.crewscope.server.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.application.agent.AgentManagementApplicationService;
import io.crewscope.application.agent.ManagedAgentView;
import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.agent.AgentConfigurableSlot;
import io.crewscope.domain.agent.AgentConfigurationHash;
import io.crewscope.domain.agent.AgentConfigurationRevision;
import io.crewscope.domain.agent.AgentConfigurationVersion;
import io.crewscope.domain.agent.AgentExecutionScope;
import io.crewscope.domain.agent.AgentOwnershipType;
import io.crewscope.domain.agent.AgentRuntimeRole;
import io.crewscope.domain.agent.AgentTemplateCapabilities;
import io.crewscope.domain.agent.AgentTemplateCapability;
import io.crewscope.domain.agent.AgentTemplateDefinition;
import io.crewscope.domain.agent.AgentTemplateKey;
import io.crewscope.domain.agent.AgentTemplatePolicy;
import io.crewscope.domain.agent.AgentTemplatePublisherScope;
import io.crewscope.domain.agent.AgentToolKey;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.team.TeamInitialization;
import io.crewscope.domain.teamobserver.TeamObserverTemplate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/** M5-A02 route, ETag, receipt and public Agent/template DTO tests. */
class AgentManagementControllerM5A02Test {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-24T14:00:00Z");

    private final OrganizationId organizationId = OrganizationId.generate();
    private final Principal actor = Principal.create(
            PrincipalId.generate(),
            PrincipalScope.organization(organizationId),
            PrincipalType.USER,
            Optional.empty(),
            "Agent owner",
            Optional.empty(),
            PrincipalVisibility.ORGANIZATION,
            NOW);
    private final TeamInitialization initialization =
            TeamInitialization.create(actor, "Agent API team", NOW);
    private final AgentTemplateDefinition coding = AgentTemplateDefinition.publishInitial(
            AgentTemplatePublisherScope.organization(organizationId),
            new AgentTemplateKey("coding"),
            AgentRuntimeRole.SPECIALIST,
            Set.of(AgentOwnershipType.USER, AgentOwnershipType.TEAM),
            Set.of(AgentExecutionScope.PERSONAL, AgentExecutionScope.TEAM),
            AgentTemplateCapabilities.define(
                    Set.of(new AgentTemplateCapability("code.execute")),
                    Set.of(new AgentTemplateCapability("model.tool-calling"))),
            AgentTemplatePolicy.define(
                    "Private system baseline that must not be returned.",
                    Set.of(new AgentToolKey("coding.inspect")),
                    Set.of("coding-baseline"),
                    Optional.of("{\"type\":\"object\"}"),
                    Set.of(AgentConfigurableSlot.DISPLAY_NAME),
                    Set.of(AgentConfigurableSlot.MODEL_BINDING)),
            actor.id(),
            NOW);
    private final ManagedAgentView personal = new ManagedAgentView(
            initialization.ownerPersonalAgent().agentPrincipal(),
            initialization.ownerPersonalAgent().agentProfile(),
            Optional.empty());

    private AgentManagementApplicationService service;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        service = mock(AgentManagementApplicationService.class);
        TeamRequestIdentityResolver resolver = (authentication, organization, correlationId) ->
                Mono.just(new TeamAccessContext(actor, false));
        client = WebTestClient.bindToController(
                        new AgentManagementController(service, resolver))
                .controllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void returnsOnlyPublicTemplateCatalogFields() {
        when(service.listTemplates(any(), any(), any(), any(), any(Integer.class), any(Integer.class)))
                .thenReturn(List.of(coding));

        client.get()
                .uri(base() + "/agent-templates?ownershipType=USER")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody()
                .jsonPath("$.items[0].key").isEqualTo("coding")
                .jsonPath("$.items[0].runtimeRole").isEqualTo("SPECIALIST")
                .jsonPath("$.items[0].creatable").isEqualTo(true)
                .jsonPath("$.items[0].platformManaged").isEqualTo(false)
                .jsonPath("$.items[0].approvedSkillKeys[0]").isEqualTo("coding-baseline")
                .jsonPath("$.items[0].systemPromptBaseline").doesNotExist()
                .jsonPath("$.items[0].structuredOutputSchema").doesNotExist()
                .jsonPath("$.items[0].allowedTools").doesNotExist();
    }

    @Test
    void marksBuiltInTeamObserverAsVisibleButNotCreatable() {
        AgentTemplateDefinition observer =
                TeamObserverTemplate.create(organizationId, actor.id(), NOW);
        when(service.listTemplates(any(), any(), any(), any(), any(Integer.class), any(Integer.class)))
                .thenReturn(List.of(observer));

        client.get()
                .uri(base() + "/agent-templates?ownershipType=TEAM")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.items[0].key").isEqualTo("team-observer")
                .jsonPath("$.items[0].creatable").isEqualTo(false)
                .jsonPath("$.items[0].platformManaged").isEqualTo(true)
                .jsonPath("$.items[0].administratorConfigurableSlots[0]")
                .isEqualTo("BUDGET");
    }

    @Test
    void returnsAgentDetailWithStrongEtagAndCurrentConfigurationSummary() {
        when(service.get(any(), any(), any(), any())).thenReturn(personal);

        client.get()
                .uri(base() + "/agent-profiles/" + personal.profile().id())
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(ApiHeaders.ETAG, "\"0\"")
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody()
                .jsonPath("$.displayName").isEqualTo(personal.principal().displayName())
                .jsonPath("$.defaultProfile").isEqualTo(true)
                .jsonPath("$.runtimeRole").isEqualTo("PERSONAL_ASSISTANT")
                .jsonPath("$.currentConfigurationRevision").doesNotExist()
                .jsonPath("$.systemPrompt").doesNotExist();
    }

    @Test
    void createsAgentThroughReceiptWithoutAcceptingInternalIdentityCoordinates() {
        CommandReceipt receipt = new CommandReceipt(
                UUID.randomUUID(), UUID.randomUUID(), 0, UUID.randomUUID());
        when(service.create(any(), any(), any()))
                .thenReturn(CommandExecution.completed(personal.profile(), receipt));

        client.post()
                .uri(base() + "/agent-profiles")
                .header(ApiHeaders.IDEMPOTENCY_KEY, "m5-a02-http-create")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"publisherType":"ORGANIZATION","templateKey":"coding",
                         "templateVersion":1,"ownershipType":"USER","displayName":"Java Coding"}
                        """)
                .exchange()
                .expectStatus().isAccepted()
                .expectBody()
                .jsonPath("$.domainEventId").isEqualTo(receipt.domainEventId().toString())
                .jsonPath("$.principalId").doesNotExist()
                .jsonPath("$.principalType").doesNotExist();
    }

    @Test
    void requiresIdempotencyAndStrongVersionHeadersForLifecycleCommands() {
        client.post()
                .uri(base() + "/agent-profiles/" + personal.profile().id() + "/disable")
                .exchange()
                .expectStatus().isEqualTo(428);

        client.post()
                .uri(base() + "/agent-profiles/" + personal.profile().id() + "/disable")
                .header(ApiHeaders.IF_MATCH, "\"0\"")
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void returnsReadOnlyConfigurationEvidenceWithoutRuntimeSecretsOrPolicyPayloads() {
        AgentConfigurationVersion configuration = mock(AgentConfigurationVersion.class);
        when(configuration.revision()).thenReturn(new AgentConfigurationRevision(2));
        when(configuration.previousRevision())
                .thenReturn(Optional.of(new AgentConfigurationRevision(1)));
        when(configuration.templateVersion()).thenReturn(coding.templateVersion());
        when(configuration.templateContentHash()).thenReturn(coding.contentHash());
        when(configuration.personalModelBinding()).thenReturn(Optional.empty());
        when(configuration.teamModelBinding()).thenReturn(Optional.empty());
        when(configuration.configurationHash())
                .thenReturn(new AgentConfigurationHash("a".repeat(64)));
        when(configuration.audit()).thenReturn(AuditMetadata.createdBy(actor.id(), NOW));
        when(service.configurationHistory(any(), any(), any(), any(), any(Integer.class), any(Integer.class)))
                .thenReturn(List.of(configuration));

        client.get()
                .uri(base() + "/agent-profiles/" + personal.profile().id() + "/configurations")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody()
                .jsonPath("$.items[0].revision").isEqualTo(2)
                .jsonPath("$.items[0].previousRevision").isEqualTo(1)
                .jsonPath("$.items[0].templateContentHash")
                .isEqualTo(coding.contentHash().toString())
                .jsonPath("$.items[0].configurationHash").isEqualTo("a".repeat(64))
                .jsonPath("$.items[0].systemPromptBaseline").doesNotExist()
                .jsonPath("$.items[0].supplementalInstructions").doesNotExist()
                .jsonPath("$.items[0].enabledTools").doesNotExist()
                .jsonPath("$.items[0].credential").doesNotExist()
                .jsonPath("$.items[0].endpoint").doesNotExist()
                .jsonPath("$.items[0].providerDefinitionHash").doesNotExist();
    }

    private String base() {
        return "/api/v1/organizations/" + organizationId
                + "/teams/" + initialization.team().id();
    }
}
