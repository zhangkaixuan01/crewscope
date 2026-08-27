package io.crewscope.server.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.activity.ActivityApplicationService;
import io.crewscope.application.activity.AuthorizedActivitySnapshot;
import io.crewscope.application.activity.TeamActivitySnapshotRequest;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkProjectId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.CacheControl;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/** Exact route-bound WorkItem filter proof for M6-A01. */
class WorkItemActivityControllerM6A01Test {

  @Test
  @SuppressWarnings("unchecked")
  void fixesTheFilterToTheRouteWorkItemBeforeQuerying() {
    OrganizationId organizationId = OrganizationId.generate();
    TeamId teamId = TeamId.generate();
    WorkProjectId projectId = WorkProjectId.generate();
    WorkItemId workItemId = WorkItemId.generate();
    TeamAccessContext access = mock(TeamAccessContext.class);
    ActivityApplicationService service = mock(ActivityApplicationService.class);
    TeamRequestIdentityResolver resolver = mock(TeamRequestIdentityResolver.class);
    when(resolver.resolve(any(), eq(organizationId), any())).thenReturn(Mono.just(access));
    TeamActivityCursorCodec codec = new TeamActivityCursorCodec(
        new TeamActivityCursorKeyRing(
            "k1", Map.of("k1", Base64.getEncoder().encodeToString(new byte[32]))),
        Clock.fixed(Instant.parse("2026-08-27T00:00:00Z"), ZoneOffset.UTC),
        Duration.ofHours(1),
        Duration.ofSeconds(30));
    ObjectProvider<TeamActivityCursorCodec> codecs = mock(ObjectProvider.class);
    when(codecs.getIfAvailable()).thenReturn(codec);
    when(service.workItemSnapshot(
            eq(access), eq(projectId), eq(workItemId), any()))
        .thenReturn(new AuthorizedActivitySnapshot(
            List.of(), false, Optional.empty(), Optional.empty()));
    WebTestClient client = WebTestClient.bindToController(
            new WorkItemActivityController(service, resolver, codecs))
        .controllerAdvice(new ApiExceptionHandler())
        .build();

    client.get()
        .uri("/api/v1/organizations/"
            + organizationId
            + "/teams/"
            + teamId
            + "/work-projects/"
            + projectId
            + "/work-items/"
            + workItemId
            + "/activity/snapshot?categories=task")
        .exchange()
        .expectStatus().isOk()
        .expectHeader().cacheControl(CacheControl.noStore())
        .expectBody()
        .jsonPath("$.items.length()").isEqualTo(0);

    ArgumentCaptor<TeamActivitySnapshotRequest> request =
        ArgumentCaptor.forClass(TeamActivitySnapshotRequest.class);
    verify(service).workItemSnapshot(eq(access), eq(projectId), eq(workItemId), request.capture());
    assertEquals(Optional.of(workItemId), request.getValue().filter().workItemId());
    assertEquals(Set.of(io.crewscope.domain.activity.ActivityCategory.TASK),
        request.getValue().filter().categories());
  }
}
