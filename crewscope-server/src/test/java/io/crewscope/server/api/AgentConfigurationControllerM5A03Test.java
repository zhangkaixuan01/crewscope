package io.crewscope.server.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.application.agent.AgentConfigurationApplicationService;
import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.agent.AgentConfigurationHash;
import io.crewscope.domain.agent.AgentConfigurationRevision;
import io.crewscope.domain.agent.AgentConfigurationVersion;
import io.crewscope.domain.agent.AgentTemplateHash;
import io.crewscope.domain.agent.AgentTemplateKey;
import io.crewscope.domain.agent.AgentTemplateMemberConfiguration;
import io.crewscope.domain.agent.AgentTemplateVersion;
import io.crewscope.domain.agent.SafeModelGenerateOptions;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.policy.PolicyPackId;
import io.crewscope.domain.policy.PolicyPackReference;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workspace.AgentProfileId;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/** M5-A03 Agent configuration route, ETag, whitelist and Receipt tests. */
class AgentConfigurationControllerM5A03Test {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-24T15:00:00Z");

    private final OrganizationId organizationId = OrganizationId.generate();
    private final TeamId teamId = TeamId.generate();
    private final AgentProfileId profileId = AgentProfileId.generate();
    private final Principal actor = Principal.create(
            PrincipalId.generate(),
            PrincipalScope.organization(organizationId),
            PrincipalType.USER,
            Optional.empty(),
            "Agent owner",
            Optional.empty(),
            PrincipalVisibility.ORGANIZATION,
            NOW);

    private AgentConfigurationApplicationService service;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        service = mock(AgentConfigurationApplicationService.class);
        TeamRequestIdentityResolver resolver = (authentication, organization, correlationId) ->
                Mono.just(new TeamAccessContext(actor, false));
        client = WebTestClient.bindToController(
                        new AgentConfigurationController(service, resolver))
                .controllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void returnsCurrentRevisionWithStrongEtagAndNoRuntimeSecrets() {
        AgentConfigurationVersion configuration = configuration();
        when(service.current(any(), any(), any(), any())).thenReturn(configuration);

        client.get()
                .uri(base() + "/configurations/current")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(ApiHeaders.ETAG, "\"2\"")
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody()
                .jsonPath("$.revision").isEqualTo(2)
                .jsonPath("$.supplementalInstructions").isEqualTo("Use concise Java comments.")
                .jsonPath("$.configurationHash").isEqualTo("a".repeat(64))
                .jsonPath("$.endpoint").doesNotExist()
                .jsonPath("$.credential").doesNotExist()
                .jsonPath("$.adapterKey").doesNotExist()
                .jsonPath("$.systemPromptBaseline").doesNotExist()
                .jsonPath("$.enabledTools").doesNotExist();
    }

    @Test
    void appendsOnlyStableIdsAndControlledPreferencesThroughAReceipt() {
        CommandReceipt receipt = new CommandReceipt(
                UUID.randomUUID(), UUID.randomUUID(), 3, UUID.randomUUID());
        AgentConfigurationVersion committed = configuration();
        when(service.append(any(), any(), any(), any(Long.class), any()))
                .thenReturn(CommandExecution.completed(committed, receipt));

        client.post()
                .uri(base() + "/configurations")
                .header(ApiHeaders.IDEMPOTENCY_KEY, "m5-a03-config-http")
                .header(ApiHeaders.IF_MATCH, "\"2\"")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"teamModelBinding":{"kind":"INHERIT_TEAM_DEFAULT"},
                         "approvedSkillKeys":[],
                         "supplementalInstructions":"Use concise Java comments.",
                         "generateOptions":{"reasoningMode":"DEFAULT","maximumAttempts":1}}
                        """)
                .exchange()
                .expectStatus().isAccepted()
                .expectBody()
                .jsonPath("$.domainEventId").isEqualTo(receipt.domainEventId().toString())
                .jsonPath("$.committedVersion").isEqualTo(3)
                .jsonPath("$.configuration").doesNotExist();
    }

    @Test
    void rejectsClientControlledOrchestrationBindingAndRequiresCommandHeaders() {
        client.post()
                .uri(base() + "/configurations")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"approvedSkillKeys\":[]}")
                .exchange()
                .expectStatus().isEqualTo(428);

        client.post()
                .uri(base() + "/configurations")
                .header(ApiHeaders.IDEMPOTENCY_KEY, "m5-a03-server-binding")
                .header(ApiHeaders.IF_MATCH, "\"0\"")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"teamModelBinding":{"kind":"ORCHESTRATION_ONLY"},
                         "approvedSkillKeys":[]}
                        """)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("invalid_request");
    }

    private AgentConfigurationVersion configuration() {
        AgentConfigurationVersion value = mock(AgentConfigurationVersion.class);
        AgentTemplateMemberConfiguration memberConfiguration =
                mock(AgentTemplateMemberConfiguration.class);
        when(memberConfiguration.supplementalInstructions())
                .thenReturn(Optional.of("Use concise Java comments."));
        when(value.revision()).thenReturn(new AgentConfigurationRevision(2));
        when(value.previousRevision()).thenReturn(Optional.of(new AgentConfigurationRevision(1)));
        when(value.templateVersion())
                .thenReturn(new AgentTemplateVersion(new AgentTemplateKey("coding"), 1));
        when(value.templateContentHash())
                .thenReturn(new AgentTemplateHash("b".repeat(64)));
        when(value.personalModelBinding()).thenReturn(Optional.empty());
        when(value.teamModelBinding()).thenReturn(Optional.empty());
        when(value.templateConfiguration()).thenReturn(memberConfiguration);
        when(value.approvedSkillKeys()).thenReturn(Set.of());
        when(value.memoryPolicy()).thenReturn(Optional.empty());
        when(value.budgetPolicy()).thenReturn(Optional.empty());
        when(value.generateOptions()).thenReturn(SafeModelGenerateOptions.defaults());
        when(value.policyPack()).thenReturn(new PolicyPackReference(PolicyPackId.generate(), 1));
        when(value.configurationHash())
                .thenReturn(new AgentConfigurationHash("a".repeat(64)));
        when(value.audit()).thenReturn(AuditMetadata.createdBy(actor.id(), NOW));
        return value;
    }

    private String base() {
        return "/api/v1/organizations/" + organizationId
                + "/teams/" + teamId
                + "/agent-profiles/" + profileId;
    }
}
