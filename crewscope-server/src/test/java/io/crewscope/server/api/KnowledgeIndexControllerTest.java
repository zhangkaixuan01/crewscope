package io.crewscope.server.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.retrieval.KnowledgeIndexControlService;
import io.crewscope.application.retrieval.KnowledgeIndexJob;
import io.crewscope.application.retrieval.KnowledgeIndexJobFilter;
import io.crewscope.application.retrieval.KnowledgeIndexJobNotCancellableException;
import io.crewscope.application.retrieval.KnowledgeIndexJobNotFoundException;
import io.crewscope.application.retrieval.KnowledgeIndexJobPage;
import io.crewscope.application.retrieval.KnowledgeIndexJobPageRequest;
import io.crewscope.application.retrieval.KnowledgeIndexJobStatus;
import io.crewscope.application.retrieval.RepositoryBuildEnqueueResult;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.coding.RepositoryBindingId;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.knowledge.KnowledgeEntryId;
import io.crewscope.domain.retrieval.EmbeddingModelRevision;
import io.crewscope.domain.retrieval.RepositoryIndexKey;
import io.crewscope.domain.retrieval.SourceCommit;
import io.crewscope.domain.retrieval.chunking.ChunkingPolicy;
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
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/** Proves the M10-I01c knowledge index control-plane HTTP contract end to end. */
class KnowledgeIndexControllerTest {

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

  private KnowledgeIndexControlService service;
  private WebTestClient client;

  private String root() {
    return "/api/v1/organizations/" + organizationId + "/teams/" + teamId + "/knowledge/index";
  }

  @BeforeEach
  void setUp() {
    service = mock(KnowledgeIndexControlService.class);
    TeamRequestIdentityResolver resolver =
        (authentication, organization, correlationId) ->
            Mono.just(new TeamAccessContext(actor, false));
    client =
        WebTestClient.bindToController(new KnowledgeIndexController(service, resolver))
            .controllerAdvice(new ApiExceptionHandler())
            .build();
  }

  @Test
  void rebuildAnswersAcceptedWithTheEnqueuedCount() {
    when(service.rebuild(any(), any(), any())).thenReturn(3);

    client.post().uri(root() + "/rebuilds").exchange()
        .expectStatus().isEqualTo(202)
        .expectBody()
        .jsonPath("$.enqueued").isEqualTo(3);
  }

  @Test
  void repositoryBuildAnswersAcceptedWithTheJobOrSkipped() {
    KnowledgeIndexJob job = repositoryJob();
    when(service.enqueueRepositoryBuild(any(), any(), any(), any(), any(), any()))
        .thenReturn(RepositoryBuildEnqueueResult.accepted(job));

    client
        .post()
        .uri(root() + "/repository-builds")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(
            "{\"projectId\":\"" + UUID.randomUUID()
                + "\",\"bindingId\":\"" + UUID.randomUUID()
                + "\",\"commit\":\"" + COMMIT + "\"}")
        .exchange()
        .expectStatus()
        .isEqualTo(202)
        .expectBody()
        .jsonPath("$.enqueued").isEqualTo(1)
        .jsonPath("$.job.id").isEqualTo(job.id().toString())
        .jsonPath("$.job.source").isEqualTo("REPOSITORY")
        .jsonPath("$.job.indexKey.bindingId")
        .isEqualTo(job.indexKey().orElseThrow().repositoryBindingId().value().toString())
        .jsonPath("$.job.indexKey.commit").isEqualTo(COMMIT)
        .jsonPath("$.job.indexKey.modelKey").isEqualTo("text-embedding-v4")
        .jsonPath("$.job.claimToken").doesNotExist()
        .jsonPath("$.job.claimedBy").doesNotExist();

    when(service.enqueueRepositoryBuild(any(), any(), any(), any(), any(), any()))
        .thenReturn(RepositoryBuildEnqueueResult.skipped());
    client
        .post()
        .uri(root() + "/repository-builds")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(
            "{\"projectId\":\"" + UUID.randomUUID()
                + "\",\"bindingId\":\"" + UUID.randomUUID()
                + "\",\"commit\":\"" + COMMIT + "\"}")
        .exchange()
        .expectStatus()
        .isEqualTo(202)
        .expectBody()
        .jsonPath("$.enqueued").isEqualTo(0)
        .jsonPath("$.job").doesNotExist();
  }

  @Test
  void repositoryBuildRejectsBrokenBodiesAndMapsDomainFailures() {
    String good =
        "{\"projectId\":\"" + UUID.randomUUID()
            + "\",\"bindingId\":\"" + UUID.randomUUID()
            + "\",\"commit\":\"" + COMMIT + "\"}";

    client.post().uri(root() + "/repository-builds")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(good.replace(COMMIT, "not-a-commit")).exchange()
        .expectStatus().isBadRequest();
    client.post().uri(root() + "/repository-builds")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"bindingId\":\"" + UUID.randomUUID() + "\",\"commit\":\"" + COMMIT + "\"}")
        .exchange()
        .expectStatus().isBadRequest();

    when(service.enqueueRepositoryBuild(any(), any(), any(), any(), any(), any()))
        .thenThrow(new AggregateNotFoundException("RepositoryBinding", RepositoryBindingId.generate()));
    client.post().uri(root() + "/repository-builds")
        .contentType(MediaType.APPLICATION_JSON).bodyValue(good).exchange()
        .expectStatus().isNotFound();

    // Re-stubbing a throwing stub needs doThrow: when() would replay the previous throw.
    doThrow(new DomainValidationException(
        "repositoryIndex.bindingId", "must reference an active RepositoryBinding"))
        .when(service)
        .enqueueRepositoryBuild(any(), any(), any(), any(), any(), any());
    client.post().uri(root() + "/repository-builds")
        .contentType(MediaType.APPLICATION_JSON).bodyValue(good).exchange()
        .expectStatus().isEqualTo(422);
  }

  @Test
  void listAnswersNoStoreAndPassesTheCursorAndFiltersThrough() {
    KnowledgeIndexJob first =
        KnowledgeIndexJob.knowledgeEntry(
            UUID.randomUUID(), organizationId, teamId, KnowledgeEntryId.generate(),
            actor.id(), NOW);
    when(service.list(any(), any(), any(), any(), any()))
        .thenReturn(new KnowledgeIndexJobPage(List.of(first), Optional.of(first.id())));

    UUID after = UUID.randomUUID();
    client.get().uri(root() + "/jobs?source=REPOSITORY&status=QUEUED&after=" + after + "&limit=2")
        .exchange()
        .expectStatus().isOk()
        .expectHeader().cacheControl(org.springframework.http.CacheControl.noStore())
        .expectBody()
        .jsonPath("$.items[0].id").isEqualTo(first.id().toString())
        .jsonPath("$.items[0].status").isEqualTo("QUEUED")
        .jsonPath("$.items[0].entryId")
        .isEqualTo(first.entryId().orElseThrow().value().toString())
        .jsonPath("$.nextAfter").isEqualTo(first.id().toString());

    ArgumentCaptor<KnowledgeIndexJobFilter> filter =
        ArgumentCaptor.forClass(KnowledgeIndexJobFilter.class);
    ArgumentCaptor<KnowledgeIndexJobPageRequest> page =
        ArgumentCaptor.forClass(KnowledgeIndexJobPageRequest.class);
    verify(service).list(any(), any(), any(), filter.capture(), page.capture());
    assertEquals(Optional.of(io.crewscope.application.retrieval.KnowledgeIndexJobSource.REPOSITORY),
        filter.getValue().source());
    assertEquals(Optional.of(KnowledgeIndexJobStatus.QUEUED), filter.getValue().status());
    assertEquals(after, page.getValue().afterJobId().orElseThrow());
    assertEquals(2, page.getValue().limit());

    client.get().uri(root() + "/jobs?after=not-a-uuid").exchange().expectStatus().isBadRequest();
    client.get().uri(root() + "/jobs?source=MYSTERY").exchange().expectStatus().isBadRequest();
    client.get().uri(root() + "/jobs?limit=101").exchange().expectStatus().isBadRequest();
  }

  @Test
  void jobDetailSharesOneNotFoundShapeForMissingAndCrossTenant() {
    UUID jobId = UUID.randomUUID();
    when(service.job(any(), any(), any(), any()))
        .thenThrow(new KnowledgeIndexJobNotFoundException(jobId));

    client.get().uri(root() + "/jobs/" + jobId).exchange()
        .expectStatus().isNotFound()
        .expectBody()
        .jsonPath("$.code").isEqualTo("knowledge_index_job_not_found")
        .jsonPath("$.details.jobId").isEqualTo(jobId.toString());
  }

  @Test
  void cancelReplaysTheSnapshotConflictsOrMisses() {
    KnowledgeIndexJob cancelled =
        KnowledgeIndexJob.knowledgeEntry(
                UUID.randomUUID(), organizationId, teamId, KnowledgeEntryId.generate(),
                actor.id(), NOW)
            .cancelled(io.crewscope.application.retrieval.KnowledgeIndexFailureCodes.CANCELLED, NOW);
    when(service.cancel(any(), any(), any(), any())).thenReturn(cancelled);

    client.post().uri(root() + "/jobs/" + cancelled.id() + "/cancel").exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.status").isEqualTo("CANCELLED")
        .jsonPath("$.failureCode").isEqualTo("CANCELLED")
        .jsonPath("$.leaseExpiresAt").doesNotExist();

    when(service.cancel(any(), any(), any(), any()))
        .thenThrow(new KnowledgeIndexJobNotCancellableException(
            cancelled.id(), KnowledgeIndexJobStatus.CHUNKING));
    client.post().uri(root() + "/jobs/" + cancelled.id() + "/cancel").exchange()
        .expectStatus().isEqualTo(409)
        .expectBody()
        .jsonPath("$.code").isEqualTo("knowledge_index_job_not_cancellable")
        .jsonPath("$.details.status").isEqualTo("CHUNKING");

    // Re-stubbing a throwing stub needs doThrow: when() would replay the previous throw.
    doThrow(new KnowledgeIndexJobNotFoundException(cancelled.id()))
        .when(service)
        .cancel(any(), any(), any(), any());
    client.post().uri(root() + "/jobs/" + cancelled.id() + "/cancel").exchange()
        .expectStatus().isNotFound();
  }

  @Test
  void policyDenialMapsToForbidden() {
    when(service.job(any(), any(), any(), any()))
        .thenThrow(new PolicyDeniedException("manage this Team's knowledge"));

    client.get().uri(root() + "/jobs/" + UUID.randomUUID()).exchange()
        .expectStatus().isForbidden();
  }

  private KnowledgeIndexJob repositoryJob() {
    RepositoryIndexKey indexKey =
        new RepositoryIndexKey(
            organizationId,
            teamId,
            RepositoryBindingId.generate(),
            new SourceCommit(COMMIT),
            ChunkingPolicy.defaults().policyHash(),
            new EmbeddingModelRevision("text-embedding-v4", 1024, 3));
    return KnowledgeIndexJob.repositoryBuild(
        UUID.randomUUID(), organizationId, teamId, WorkProjectId.generate(),
        indexKey, actor.id(), NOW);
  }
}
