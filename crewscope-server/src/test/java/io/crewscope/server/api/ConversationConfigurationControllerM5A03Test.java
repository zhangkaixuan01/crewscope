package io.crewscope.server.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.conversation.ConversationConfigurationRefreshService;
import io.crewscope.application.conversation.ConversationConfigurationStatus;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.agent.AgentConfigurationHash;
import io.crewscope.domain.agent.AgentConfigurationRevision;
import io.crewscope.domain.agent.AgentConfigurationVersion;
import io.crewscope.domain.agent.AgentOwnershipType;
import io.crewscope.domain.agent.AgentRuntimeRole;
import io.crewscope.domain.agent.AgentTemplateKey;
import io.crewscope.domain.agent.AgentTemplateVersion;
import io.crewscope.domain.conversation.AgentRuntimeConfigurationPin;
import io.crewscope.domain.conversation.AgentRuntimeSession;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workspace.AgentProfileId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/** M5-A03 Conversation configuration pin, ETag and refresh Receipt tests. */
class ConversationConfigurationControllerM5A03Test {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-24T15:30:00Z");

    private final OrganizationId organizationId = OrganizationId.generate();
    private final TeamId teamId = TeamId.generate();
    private final io.crewscope.domain.conversation.ConversationId conversationId =
            io.crewscope.domain.conversation.ConversationId.generate();
    private final Principal actor = Principal.create(
            PrincipalId.generate(),
            PrincipalScope.organization(organizationId),
            PrincipalType.USER,
            Optional.empty(),
            "Conversation owner",
            Optional.empty(),
            PrincipalVisibility.ORGANIZATION,
            NOW);

    private ConversationConfigurationRefreshService service;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        service = mock(ConversationConfigurationRefreshService.class);
        TeamRequestIdentityResolver resolver = (authentication, organization, correlationId) ->
                Mono.just(new TeamAccessContext(actor, false));
        client = WebTestClient.bindToController(
                        new ConversationConfigurationController(service, resolver))
                .controllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void exposesOnlyPinnedAndCurrentEvidenceWithStrongSessionEtag() {
        AgentRuntimeSession session = session();
        AgentConfigurationVersion current = configuration(3, "c");
        ConversationConfigurationStatus status =
                ConversationConfigurationStatus.from(session, current);
        when(service.status(any(), any(), any(), any()))
                .thenReturn(status);

        client.get()
                .uri(base() + "/agent-configuration")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(ApiHeaders.ETAG, "\"7\"")
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody()
                .jsonPath("$.pinnedConfigurationRevision").isEqualTo(2)
                .jsonPath("$.currentConfigurationRevision").isEqualTo(3)
                .jsonPath("$.refreshRequired").isEqualTo(true)
                .jsonPath("$.modelId").doesNotExist()
                .jsonPath("$.credential").doesNotExist();
    }

    @Test
    void refreshesThroughIdempotencyAndStrongSessionVersion() {
        CommandReceipt receipt = new CommandReceipt(
                UUID.randomUUID(), UUID.randomUUID(), 8, UUID.randomUUID());
        AgentRuntimeSession refreshed = session();
        when(service.refresh(any(), any(), any(), any(Long.class)))
                .thenReturn(CommandExecution.completed(refreshed, receipt));

        client.post()
                .uri(base() + "/agent-configuration-refresh")
                .header(ApiHeaders.IDEMPOTENCY_KEY, "m5-a03-conversation-refresh")
                .header(ApiHeaders.IF_MATCH, "\"7\"")
                .exchange()
                .expectStatus().isAccepted()
                .expectBody()
                .jsonPath("$.domainEventId").isEqualTo(receipt.domainEventId().toString())
                .jsonPath("$.committedVersion").isEqualTo(8);
    }

    @Test
    void requiresBothMutationHeaders() {
        client.post()
                .uri(base() + "/agent-configuration-refresh")
                .exchange()
                .expectStatus().isEqualTo(428);

        client.post()
                .uri(base() + "/agent-configuration-refresh")
                .header(ApiHeaders.IF_MATCH, "\"7\"")
                .exchange()
                .expectStatus().isBadRequest();
    }

    private AgentRuntimeSession session() {
        AgentRuntimeSession session = mock(AgentRuntimeSession.class);
        AgentProfileId profileId = AgentProfileId.generate();
        AgentRuntimeConfigurationPin pin = new AgentRuntimeConfigurationPin(
                AgentOwnershipType.USER,
                AgentRuntimeRole.PERSONAL_ASSISTANT,
                new AgentTemplateVersion(new AgentTemplateKey("personal-assistant"), 1),
                Optional.of(new AgentConfigurationRevision(2)),
                Optional.of(new AgentConfigurationHash("b".repeat(64))));
        when(session.version()).thenReturn(7L);
        when(session.agentProfileId()).thenReturn(profileId);
        when(session.configurationPin()).thenReturn(Optional.of(pin));
        when(session.id()).thenReturn(io.crewscope.domain.conversation.AgentRuntimeSessionId
                .forPersonalConversation(
                        conversationId,
                        io.crewscope.domain.team.TeamMemberId.generate(),
                        PrincipalId.generate()));
        return session;
    }

    private AgentConfigurationVersion configuration(long revision, String hashCharacter) {
        AgentConfigurationVersion value = mock(AgentConfigurationVersion.class);
        when(value.revision()).thenReturn(new AgentConfigurationRevision(revision));
        when(value.configurationHash())
                .thenReturn(new AgentConfigurationHash(hashCharacter.repeat(64)));
        return value;
    }

    private String base() {
        return "/api/v1/organizations/" + organizationId
                + "/teams/" + teamId
                + "/conversations/" + conversationId;
    }
}
