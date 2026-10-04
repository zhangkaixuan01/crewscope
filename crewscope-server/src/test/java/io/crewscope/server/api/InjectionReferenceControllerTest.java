package io.crewscope.server.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.application.retrieval.ClaimedReferenceOutsideManifestException;
import io.crewscope.application.retrieval.FeedbackReferenceOutsideManifestException;
import io.crewscope.application.retrieval.InjectionClaimedReferenceConflictException;
import io.crewscope.application.retrieval.InjectionClaimedReferences;
import io.crewscope.application.retrieval.InjectionFeedbackKind;
import io.crewscope.application.retrieval.InjectionManifestNotSealedException;
import io.crewscope.application.retrieval.InjectionReferenceFeedback;
import io.crewscope.application.retrieval.InjectionReferenceService;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.retrieval.InjectionManifest;
import io.crewscope.domain.retrieval.InjectionManifestId;
import io.crewscope.domain.retrieval.ManifestSourceKey;
import io.crewscope.domain.retrieval.ManifestSourceRef;
import io.crewscope.domain.retrieval.ManifestSourceStage;
import io.crewscope.domain.retrieval.ManifestSourceType;
import io.crewscope.domain.retrieval.PromptBudgetSnapshot;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/**
 * Proves the M10-I02c execution evidence HTTP contract end to end: the three-zone
 * read (candidates, injected, claimed) is member-level and no-store, feedback and
 * claimed receipts answer synchronously, broken bodies split between 400 parse
 * failures and 422 domain violations, and the three dedicated 422 codes plus the
 * 409 conflict carry their evidence in the details.
 */
class InjectionReferenceControllerTest {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-10-04T09:00:00Z");
    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);

    private final OrganizationId organizationId = OrganizationId.generate();
    private final TeamId teamId = TeamId.generate();
    private final TaskId taskId = TaskId.generate();
    private final TaskExecutionId executionId = TaskExecutionId.generate();
    private final Principal actor =
            Principal.create(
                    PrincipalId.generate(),
                    PrincipalScope.organization(organizationId),
                    PrincipalType.USER,
                    Optional.empty(),
                    "Member",
                    Optional.empty(),
                    PrincipalVisibility.ORGANIZATION,
                    NOW);

    private InjectionReferenceService service;
    private WebTestClient client;

    private String root() {
        return "/api/v1/organizations/" + organizationId + "/teams/" + teamId
                + "/tasks/" + taskId + "/attempts/" + executionId + "/injection-references";
    }

    private static ManifestSourceKey knowledgeKey() {
        return new ManifestSourceKey(
                ManifestSourceType.KNOWLEDGE_ENTRY, "entry-1", 3, HASH_A);
    }

    private static ManifestSourceKey skillKey() {
        return new ManifestSourceKey(
                ManifestSourceType.SKILL_INSTRUCTION,
                "java-spring-v1_crewscope-java-spring-v1", 1, HASH_B);
    }

    @BeforeEach
    void setUp() {
        service = mock(InjectionReferenceService.class);
        TeamRequestIdentityResolver resolver =
                (authentication, organization, correlationId) ->
                        Mono.just(new TeamAccessContext(actor, false));
        client =
                WebTestClient.bindToController(new InjectionReferenceController(service, resolver))
                        .controllerAdvice(new ApiExceptionHandler())
                        .build();
    }

    @Test
    void viewAssemblesTheThreeZonesWithNoStore() {
        ManifestSourceRef injected = new ManifestSourceRef(
                ManifestSourceType.KNOWLEDGE_ENTRY, "entry-1", 3, HASH_A,
                ManifestSourceStage.INJECTED);
        ManifestSourceRef candidate = new ManifestSourceRef(
                ManifestSourceType.MEMORY_PREFERENCE, "reply-language", 4, HASH_B,
                ManifestSourceStage.CANDIDATE);
        InjectionManifest manifest = new InjectionManifest(
                InjectionManifestId.generate(), executionId, 1,
                List.of(injected, candidate), List.of(),
                new PromptBudgetSnapshot(8192, 6, 0, 0), List.of(), NOW);
        InjectionReferenceFeedback own = new InjectionReferenceFeedback(
                executionId, knowledgeKey(), actor.id(),
                InjectionFeedbackKind.NOT_APPLICABLE, NOW);
        InjectionClaimedReferences receipt = new InjectionClaimedReferences(
                executionId, 1, List.of(knowledgeKey()), NOW);
        when(service.view(any(), any(), any(), any(), any())).thenReturn(
                new InjectionReferenceService.InjectionReferenceView(
                        List.of(manifest), List.of(own), List.of(receipt)));

        client.get().uri(root()).exchange()
                .expectStatus().isOk()
                .expectHeader().cacheControl(CacheControl.noStore())
                .expectBody()
                .jsonPath("$.executionId").isEqualTo(executionId.value().toString())
                .jsonPath("$.taskId").isEqualTo(taskId.value().toString())
                .jsonPath("$.attempts[0].manifestId").isEqualTo(manifest.id().value().toString())
                .jsonPath("$.attempts[0].attempt").isEqualTo(1)
                .jsonPath("$.attempts[0].references[0].stage").isEqualTo("INJECTED")
                .jsonPath("$.attempts[0].references[0].notApplicable").isEqualTo(true)
                .jsonPath("$.attempts[0].references[1].stage").isEqualTo("CANDIDATE")
                .jsonPath("$.attempts[0].references[1].notApplicable").isEqualTo(false)
                .jsonPath("$.attempts[0].claimed[0].sourceId").isEqualTo("entry-1")
                .jsonPath("$.attempts[0].budget.totalTokens").isEqualTo(8192);

        when(service.view(any(), any(), any(), any(), any())).thenReturn(
                new InjectionReferenceService.InjectionReferenceView(
                        List.of(), List.of(), List.of()));
        client.get().uri(root()).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.attempts").isEmpty();
    }

    @Test
    void feedbackAnswersTheStoredKeyAndSplitsBrokenBodies() {
        when(service.submitFeedback(any(), any(), any(), any(), any(), any()))
                .thenReturn(new InjectionReferenceFeedback(
                        executionId, knowledgeKey(), actor.id(),
                        InjectionFeedbackKind.NOT_APPLICABLE, NOW));

        client.post().uri(root() + "/feedback")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"type\":\"KNOWLEDGE_ENTRY\",\"sourceId\":\"entry-1\","
                        + "\"version\":\"3\",\"contentHash\":\"" + HASH_A + "\"}")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().cacheControl(CacheControl.noStore())
                .expectBody()
                .jsonPath("$.type").isEqualTo("KNOWLEDGE_ENTRY")
                .jsonPath("$.sourceId").isEqualTo("entry-1")
                .jsonPath("$.version").isEqualTo(3);

        client.post().uri(root() + "/feedback")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"type\":\"MYSTERY\",\"sourceId\":\"entry-1\","
                        + "\"version\":\"3\",\"contentHash\":\"" + HASH_A + "\"}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.details.field").isEqualTo("type");
        client.post().uri(root() + "/feedback")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"type\":\"KNOWLEDGE_ENTRY\",\"sourceId\":\"entry-1\","
                        + "\"version\":\"three\",\"contentHash\":\"" + HASH_A + "\"}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.details.field").isEqualTo("version");
        // A well-formed-but-invalid hash is a domain violation, not a parse failure.
        client.post().uri(root() + "/feedback")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"type\":\"KNOWLEDGE_ENTRY\",\"sourceId\":\"entry-1\","
                        + "\"version\":\"3\",\"contentHash\":\"" + "a".repeat(63) + "\"}")
                .exchange()
                .expectStatus().value(status -> org.junit.jupiter.api.Assertions
                        .assertEquals(422, status));
    }

    @Test
    void claimedAnswersTheReceiptAndMapsTheDedicatedCodes() {
        when(service.submitClaimed(any(), any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(new InjectionClaimedReferences(
                        executionId, 1, List.of(knowledgeKey()), NOW));

        client.post().uri(root() + "/claimed")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"attempt\":\"1\",\"references\":[{\"type\":\"KNOWLEDGE_ENTRY\","
                        + "\"sourceId\":\"entry-1\",\"version\":\"3\","
                        + "\"contentHash\":\"" + HASH_A + "\"}]}")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().cacheControl(CacheControl.noStore())
                .expectBody()
                .jsonPath("$.attempt").isEqualTo(1)
                .jsonPath("$.claimed[0].sourceId").isEqualTo("entry-1");

        client.post().uri(root() + "/claimed")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"attempt\":\"0\",\"references\":[]}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.details.field").isEqualTo("attempt");

        doThrow(new InjectionManifestNotSealedException(executionId, 2))
                .when(service)
                .submitClaimed(any(), any(), any(), any(), any(), anyInt(), any());
        client.post().uri(root() + "/claimed")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"attempt\":\"2\",\"references\":[]}")
                .exchange()
                .expectStatus().isEqualTo(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY)
                .expectBody()
                .jsonPath("$.code").isEqualTo("injection_manifest_not_sealed")
                .jsonPath("$.details.attempt").isEqualTo("2");

        doThrow(new ClaimedReferenceOutsideManifestException(
                executionId, 1, List.of(skillKey())))
                .when(service)
                .submitClaimed(any(), any(), any(), any(), any(), anyInt(), any());
        client.post().uri(root() + "/claimed")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"attempt\":\"1\",\"references\":[]}")
                .exchange()
                .expectStatus().isEqualTo(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY)
                .expectBody()
                .jsonPath("$.code").isEqualTo("claimed_reference_outside_manifest")
                .jsonPath("$.details.outside")
                .isEqualTo("[SKILL_INSTRUCTION:java-spring-v1_crewscope-java-spring-v1:1:"
                        + HASH_B + "]");

        doThrow(new InjectionClaimedReferenceConflictException(
                new InjectionClaimedReferences(executionId, 1, List.of(knowledgeKey()), NOW)))
                .when(service)
                .submitClaimed(any(), any(), any(), any(), any(), anyInt(), any());
        client.post().uri(root() + "/claimed")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"attempt\":\"1\",\"references\":[]}")
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.code").isEqualTo("injection_claimed_reference_conflict")
                .jsonPath("$.details.storedClaimed")
                .isEqualTo("[KNOWLEDGE_ENTRY:entry-1:3:" + HASH_A + "]");
    }

    @Test
    void allThreeEndpointsShareTheUniformNotFoundShape() {
        doThrow(new AggregateNotFoundException("TaskExecution", executionId))
                .when(service)
                .view(any(), any(), any(), any(), any());
        client.get().uri(root()).exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.code").isEqualTo("aggregate_not_found");

        doThrow(new FeedbackReferenceOutsideManifestException(executionId, knowledgeKey()))
                .when(service)
                .submitFeedback(any(), any(), any(), any(), any(), any());
        client.post().uri(root() + "/feedback")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"type\":\"KNOWLEDGE_ENTRY\",\"sourceId\":\"entry-1\","
                        + "\"version\":\"3\",\"contentHash\":\"" + HASH_A + "\"}")
                .exchange()
                .expectStatus().isEqualTo(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY)
                .expectBody()
                .jsonPath("$.code").isEqualTo("feedback_reference_outside_manifest")
                .jsonPath("$.details.source")
                .isEqualTo("KNOWLEDGE_ENTRY:entry-1:3:" + HASH_A);
    }
}
