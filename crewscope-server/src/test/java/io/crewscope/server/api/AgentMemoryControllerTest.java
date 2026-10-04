package io.crewscope.server.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.application.memory.AgentMemoryClearance;
import io.crewscope.application.memory.AgentMemoryService;
import io.crewscope.application.memory.AgentMemoryView;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.agent.AgentMemoryEntry;
import io.crewscope.domain.agent.AgentMemoryKey;
import io.crewscope.domain.agent.AgentMemoryOwnerKey;
import io.crewscope.domain.agent.AgentMemoryPolicy;
import io.crewscope.domain.agent.AgentMemoryPolicyReference;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workspace.AgentProfileId;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/**
 * M10-I02a HTTP boundary: the three view states over one GET, the synchronous clear
 * receipt, and the shared error matrix (invalid path field 400, self-only guard 403,
 * cross-team profile 404, unready team 422) — all through the common exception handler.
 */
class AgentMemoryControllerTest {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-10-04T10:00:00Z");

    private final OrganizationId organizationId = OrganizationId.generate();
    private final TeamId teamId = TeamId.generate();
    private final AgentProfileId profileId = AgentProfileId.generate();
    private final Principal actor = Principal.create(
            PrincipalId.generate(),
            PrincipalScope.organization(organizationId),
            PrincipalType.USER,
            Optional.empty(),
            "Memory owner",
            Optional.empty(),
            PrincipalVisibility.ORGANIZATION,
            NOW);

    private AgentMemoryService service;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        service = mock(AgentMemoryService.class);
        TeamRequestIdentityResolver resolver = (authentication, organization, correlationId) ->
                Mono.just(new TeamAccessContext(actor, false));
        client = WebTestClient.bindToController(new AgentMemoryController(service, resolver))
                .controllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void anUnconfiguredAgentAnswersAnEmptyView() {
        when(service.view(any(), any(), any(), any()))
                .thenReturn(new AgentMemoryView(Optional.empty(), Optional.empty(), 0, List.of()));

        client.get().uri(base() + "/memory")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody()
                .jsonPath("$.policyReference").doesNotExist()
                .jsonPath("$.policy").doesNotExist()
                .jsonPath("$.degraded").doesNotExist()
                .jsonPath("$.clearanceGeneration").isEqualTo(0)
                .jsonPath("$.entries").isArray()
                .jsonPath("$.entryCount").isEqualTo(0);
    }

    @Test
    void viewAnswersEntriesOfTheConfiguredPolicySpace() {
        when(service.view(any(), any(), any(), any())).thenReturn(new AgentMemoryView(
                Optional.of(reference()),
                Optional.of(AgentMemoryPolicy.defaults()),
                3,
                List.of(entry("reply-language", "简体中文"))));

        client.get().uri(base() + "/memory")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody()
                .jsonPath("$.policyReference.policyId")
                .isEqualTo(AgentMemoryPolicy.DEFAULT_POLICY_ID.toString())
                .jsonPath("$.policyReference.version").isEqualTo(1)
                .jsonPath("$.policy.ttlDays").isEqualTo(90)
                .jsonPath("$.policy.maxEntriesPerOwner").isEqualTo(100)
                .jsonPath("$.policy.valueMaxBytes").isEqualTo(1024)
                .jsonPath("$.degraded").doesNotExist()
                .jsonPath("$.clearanceGeneration").isEqualTo(3)
                .jsonPath("$.entries[0].memoryKey").isEqualTo("reply-language")
                .jsonPath("$.entries[0].value").isEqualTo("简体中文")
                .jsonPath("$.entries[0].version").isEqualTo(0)
                .jsonPath("$.entries[0].expiresAt").isNotEmpty()
                .jsonPath("$.entryCount").isEqualTo(1);
    }

    @Test
    void anUnresolvablePolicyReferenceDegradesInsteadOfAnsweringEmpty() {
        when(service.view(any(), any(), any(), any())).thenReturn(new AgentMemoryView(
                Optional.of(new AgentMemoryPolicyReference(UUID.randomUUID(), 1)),
                Optional.empty(), 0, List.of()));

        client.get().uri(base() + "/memory")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody()
                .jsonPath("$.policyReference.version").isEqualTo(1)
                .jsonPath("$.policy").doesNotExist()
                .jsonPath("$.degraded").isEqualTo("POLICY_UNAVAILABLE")
                .jsonPath("$.entries").isArray()
                .jsonPath("$.entryCount").isEqualTo(0);
    }

    @Test
    void clearAnswersASynchronousReceipt() {
        when(service.clear(any(), any(), any(), any()))
                .thenReturn(new AgentMemoryClearance(3, 1));

        client.delete().uri(base() + "/memory")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody()
                .jsonPath("$.clearedCount").isEqualTo(3)
                .jsonPath("$.clearanceGeneration").isEqualTo(1);
    }

    @Test
    void anInvalidProfileIdAnswersBadRequestWithTheField() {
        client.get()
                .uri("/api/v1/organizations/" + organizationId.value()
                        + "/teams/" + teamId.value()
                        + "/agent-profiles/not-a-uuid/memory")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("invalid_request")
                .jsonPath("$.details.field").isEqualTo("profileId");
    }

    @Test
    void theErrorMatrixMapsTheFrozenGuards() {
        doThrow(new PolicyDeniedException("access this Agent's assistant memory"))
                .when(service).view(any(), any(), any(), any());
        client.get().uri(base() + "/memory")
                .exchange()
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.code").isEqualTo("policy_denied");

        doThrow(new AggregateNotFoundException("AgentProfile", profileId))
                .when(service).view(any(), any(), any(), any());
        client.get().uri(base() + "/memory")
                .exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.code").isEqualTo("aggregate_not_found");

        doThrow(new DomainValidationException("team.initializationStatus", "must be READY"))
                .when(service).view(any(), any(), any(), any());
        client.get().uri(base() + "/memory")
                .exchange()
                .expectStatus().isEqualTo(422)
                .expectBody().jsonPath("$.code").isEqualTo("invalid_value");
    }

    // ---------------------------------------------------------------- fixtures

    private String base() {
        return "/api/v1/organizations/" + organizationId.value()
                + "/teams/" + teamId.value()
                + "/agent-profiles/" + profileId.value();
    }

    private static AgentMemoryPolicyReference reference() {
        return AgentMemoryPolicy.defaults().reference();
    }

    private AgentMemoryEntry entry(String memoryKey, String value) {
        return AgentMemoryEntry.write(
                new AgentMemoryOwnerKey(organizationId, teamId, profileId, actor.id()),
                reference(),
                new AgentMemoryKey(memoryKey),
                value,
                3,
                UtcTimestamp.from(NOW.value().plus(Duration.ofDays(90))),
                actor.id(),
                NOW);
    }
}
