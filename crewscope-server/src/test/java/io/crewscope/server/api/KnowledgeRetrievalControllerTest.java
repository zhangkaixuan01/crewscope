package io.crewscope.server.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.retrieval.KnowledgeRetrievalQuery;
import io.crewscope.application.retrieval.KnowledgeRetrievalResult;
import io.crewscope.application.retrieval.KnowledgeRetrievalService;
import io.crewscope.application.retrieval.RetrievalCandidate;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.coding.RepositoryBindingId;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.knowledge.KnowledgeEntryId;
import io.crewscope.domain.knowledge.KnowledgeEntryRevision;
import io.crewscope.domain.retrieval.DegradationReasonCode;
import io.crewscope.domain.retrieval.ManifestSourceType;
import io.crewscope.domain.retrieval.SourceCommit;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamInitialization;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/** Proves the M10-A01 unified retrieval preview HTTP contract end to end. */
class KnowledgeRetrievalControllerTest {

  private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-10-04T09:00:00Z");
  private static final String COMMIT = "0123456789012345678901234567890123456789";

  private final OrganizationId organizationId = OrganizationId.generate();
  private final Principal actor =
      Principal.create(
          PrincipalId.generate(),
          PrincipalScope.organization(organizationId),
          PrincipalType.USER,
          Optional.empty(),
          "Owner",
          Optional.empty(),
          PrincipalVisibility.ORGANIZATION,
          NOW);
  private final TeamInitialization initialization = TeamInitialization.create(actor, "Platform", NOW);
  private final TeamId teamId = initialization.team().id();

  private KnowledgeRetrievalService service;
  private WebTestClient client;

  private String preview() {
    return "/api/v1/organizations/" + organizationId + "/teams/" + teamId
        + "/knowledge/knowledge-retrieval:preview";
  }

  @BeforeEach
  void setUp() {
    service = mock(KnowledgeRetrievalService.class);
    TeamRequestIdentityResolver resolver =
        (authentication, organization, correlationId) ->
            Mono.just(new TeamAccessContext(actor, false));
    client =
        WebTestClient.bindToController(new KnowledgeRetrievalController(service, resolver))
            .controllerAdvice(new ApiExceptionHandler())
            .build();
  }

  @Test
  void previewAnswersNoStoreWithBothCandidateShapesAndTheDefaultBudget() {
    KnowledgeEntryId entryId = KnowledgeEntryId.generate();
    RepositoryBindingId bindingId = RepositoryBindingId.generate();
    RetrievalCandidate knowledge =
        new RetrievalCandidate(
            ManifestSourceType.KNOWLEDGE_ENTRY,
            1,
            0.9,
            new RetrievalCandidate.KnowledgeEntryHit(
                entryId, new KnowledgeEntryRevision(2), "Onboarding",
                "a".repeat(64), "Read the handbook first."),
            List.of());
    RetrievalCandidate chunks =
        new RetrievalCandidate(
            ManifestSourceType.REPOSITORY_CHUNK,
            2,
            0.85,
            null,
            List.of(new RetrievalCandidate.RepositoryFragment(
                bindingId, new SourceCommit(COMMIT), 7L, 3,
                "docs/deploy.md", "markdown", 11, 20, "b".repeat(64), "span content")));
    when(service.retrieve(any(), any(), any(), any()))
        .thenReturn(new KnowledgeRetrievalResult(List.of(knowledge, chunks), List.of()));

    client.post().uri(preview())
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"query\":\"how do we deploy\"}")
        .exchange()
        .expectStatus().isOk()
        .expectHeader().cacheControl(CacheControl.noStore())
        .expectBody()
        .jsonPath("$.candidates.length()").isEqualTo(2)
        .jsonPath("$.candidates[0].source").isEqualTo("KNOWLEDGE_ENTRY")
        .jsonPath("$.candidates[0].rank").isEqualTo(1)
        .jsonPath("$.candidates[0].score").isEqualTo(0.9)
        .jsonPath("$.candidates[0].entry.entryId").isEqualTo(entryId.value().toString())
        .jsonPath("$.candidates[0].entry.revision").isEqualTo(2)
        .jsonPath("$.candidates[0].entry.title").isEqualTo("Onboarding")
        .jsonPath("$.candidates[0].entry.content").isEqualTo("Read the handbook first.")
        .jsonPath("$.candidates[0].fragments.length()").isEqualTo(0)
        .jsonPath("$.candidates[1].source").isEqualTo("REPOSITORY_CHUNK")
        .jsonPath("$.candidates[1].entry").doesNotExist()
        .jsonPath("$.candidates[1].fragments[0].path").isEqualTo("docs/deploy.md")
        .jsonPath("$.candidates[1].fragments[0].bindingId").isEqualTo(bindingId.value().toString())
        .jsonPath("$.candidates[1].fragments[0].commit").isEqualTo(COMMIT)
        .jsonPath("$.candidates[1].fragments[0].generationBuildSequence").isEqualTo(7)
        .jsonPath("$.candidates[1].fragments[0].startLine").isEqualTo(11)
        .jsonPath("$.candidates[1].fragments[0].endLine").isEqualTo(20)
        .jsonPath("$.candidates[1].fragments[0].content").isEqualTo("span content")
        .jsonPath("$.degraded.length()").isEqualTo(0)
        .jsonPath("$.meta.topK").isEqualTo(8)
        .jsonPath("$.meta.sources.length()").isEqualTo(1)
        .jsonPath("$.meta.sources[0]").isEqualTo("KNOWLEDGE_ENTRY");

    ArgumentCaptor<KnowledgeRetrievalQuery> captor =
        ArgumentCaptor.forClass(KnowledgeRetrievalQuery.class);
    verify(service).retrieve(any(), any(), any(), captor.capture());
    KnowledgeRetrievalQuery passed = captor.getValue();
    assertEquals("how do we deploy", passed.query());
    // Without a repository target the default searches the reachable source only.
    assertEquals(Set.of(ManifestSourceType.KNOWLEDGE_ENTRY), passed.sources());
    assertEquals(8, passed.topK());
    assertEquals(Optional.empty(), Optional.ofNullable(passed.repository()));
  }

  @Test
  void repositoryBodiesPassTheTargetAndExplicitSourcesThrough() {
    when(service.retrieve(any(), any(), any(), any()))
        .thenReturn(new KnowledgeRetrievalResult(List.of(), List.of()));
    UUID project = UUID.randomUUID();
    UUID binding = UUID.randomUUID();

    client.post().uri(preview())
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(
            "{\"query\":\"deploy\",\"sources\":[\"REPOSITORY_CHUNK\"],\"topK\":3,"
                + "\"repository\":{\"projectId\":\"" + project
                + "\",\"bindingId\":\"" + binding
                + "\",\"commit\":\"" + COMMIT + "\"}}")
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.meta.topK").isEqualTo(3)
        .jsonPath("$.meta.sources.length()").isEqualTo(1);

    ArgumentCaptor<KnowledgeRetrievalQuery> captor =
        ArgumentCaptor.forClass(KnowledgeRetrievalQuery.class);
    verify(service).retrieve(any(), any(), any(), captor.capture());
    KnowledgeRetrievalQuery passed = captor.getValue();
    assertEquals(Set.of(ManifestSourceType.REPOSITORY_CHUNK), passed.sources());
    assertEquals(3, passed.topK());
    KnowledgeRetrievalQuery.RepositoryTarget target = passed.repository();
    assertEquals(new WorkProjectId(project), target.projectId());
    assertEquals(new RepositoryBindingId(binding), target.bindingId());
    assertEquals(COMMIT, target.commit().value());
  }

  @Test
  void degradedAnswersAreStill200WithExplicitCodes() {
    when(service.retrieve(any(), any(), any(), any())).thenReturn(
        new KnowledgeRetrievalResult(
            List.of(), List.of(DegradationReasonCode.RETRIEVAL_DISABLED)));

    client.post().uri(preview())
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"query\":\"how do we deploy\"}")
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.candidates.length()").isEqualTo(0)
        .jsonPath("$.degraded[0]").isEqualTo("RETRIEVAL_DISABLED");
  }

  @Test
  void rejectsBrokenBodiesAndMapsDomainFailures() {
    java.util.function.Consumer<String> expectBadRequest =
        body -> client.post().uri(preview())
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchange()
            .expectStatus().isBadRequest();

    expectBadRequest.accept("{\"query\":\"\"}");
    expectBadRequest.accept("{\"query\":\"" + "x".repeat(33_001) + "\"}");
    expectBadRequest.accept("{\"query\":\"q\",\"sources\":[]}");
    expectBadRequest.accept("{\"query\":\"q\",\"sources\":[\"MYSTERY\"]}");
    expectBadRequest.accept("{\"query\":\"q\",\"topK\":21}");
    expectBadRequest.accept("{\"query\":\"q\",\"topK\":0}");
    expectBadRequest.accept("{\"query\":\"q\",\"sources\":[\"REPOSITORY_CHUNK\"]}");
    expectBadRequest.accept(
        "{\"query\":\"q\",\"sources\":[\"REPOSITORY_CHUNK\"],"
            + "\"repository\":{\"projectId\":\"" + UUID.randomUUID()
            + "\",\"bindingId\":\"" + UUID.randomUUID()
            + "\",\"commit\":\"not-a-commit\"}}");

    // Re-stubbing a throwing stub needs doThrow: when() would replay the previous throw.
    org.mockito.Mockito.doThrow(new PolicyDeniedException("access this Team's knowledge"))
        .when(service).retrieve(any(), any(), any(), any());
    client.post().uri(preview())
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"query\":\"q\"}")
        .exchange()
        .expectStatus().isForbidden();

    org.mockito.Mockito.doThrow(new AggregateNotFoundException(
            "RepositoryBinding", RepositoryBindingId.generate()))
        .when(service).retrieve(any(), any(), any(), any());
    client.post().uri(preview())
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"query\":\"q\"}")
        .exchange()
        .expectStatus().isNotFound();

    org.mockito.Mockito.doThrow(new DomainValidationException(
            "repositoryIndex.bindingId", "must reference an active RepositoryBinding"))
        .when(service).retrieve(any(), any(), any(), any());
    client.post().uri(preview())
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"query\":\"q\"}")
        .exchange()
        .expectStatus().isEqualTo(422);
  }
}
