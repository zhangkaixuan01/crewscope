package io.crewscope.server.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.inbox.InboxApplicationService;
import io.crewscope.application.inbox.InboxCounts;
import io.crewscope.application.inbox.InboxCursor;
import io.crewscope.application.inbox.InboxCursorExpiredException;
import io.crewscope.application.inbox.InboxDispositionCommandService;
import io.crewscope.application.inbox.InboxFilter;
import io.crewscope.application.inbox.InboxItemView;
import io.crewscope.application.inbox.InboxSourceTarget;
import io.crewscope.application.inbox.InboxTypeCount;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.inbox.InboxDisposition;
import io.crewscope.domain.inbox.InboxDispositionStatus;
import io.crewscope.domain.inbox.InboxItem;
import io.crewscope.domain.inbox.InboxItemType;
import io.crewscope.domain.inbox.InboxPriority;
import io.crewscope.domain.inbox.InboxSource;
import io.crewscope.domain.inbox.InboxSourceKey;
import io.crewscope.domain.inbox.InboxSourceRevision;
import io.crewscope.domain.inbox.InboxSourceType;
import io.crewscope.domain.projection.ProjectionGeneration;
import io.crewscope.domain.projection.ProjectionName;
import io.crewscope.domain.shared.event.SchemaVersion;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkProjectId;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/** Public DTO, strong ETag, idempotency headers and internal target proof for M6-A02. */
class InboxControllerM6A02Test {

    private static final OrganizationId ORGANIZATION_ID = OrganizationId.generate();
    private static final TeamId TEAM_ID = TeamId.generate();
    private static final UtcTimestamp NOW =
            UtcTimestamp.from(Instant.parse("2026-08-27T02:00:00Z"));

    private InboxApplicationService queries;
    private InboxDispositionCommandService commands;
    private TeamAccessContext access;
    private InboxItem item;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        queries = mock(InboxApplicationService.class);
        commands = mock(InboxDispositionCommandService.class);
        access = mock(TeamAccessContext.class);
        item = item();
        TeamRequestIdentityResolver resolver = mock(TeamRequestIdentityResolver.class);
        when(resolver.resolve(any(), eq(ORGANIZATION_ID), any())).thenReturn(Mono.just(access));
        client = WebTestClient.bindToController(new InboxController(queries, commands, resolver))
                .controllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void detailExposesOnlyReviewedFieldsAndAStrongDispositionEtag() {
        InboxItemView view = InboxItemView.merge(item, Optional.empty());
        when(queries.detail(access, ORGANIZATION_ID, TEAM_ID, item.id())).thenReturn(view);

        client.get()
                .uri(route("/" + item.id()))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().cacheControl(CacheControl.noStore())
                .expectHeader().valueEquals(ApiHeaders.ETAG, "\"0\"")
                .expectBody()
                .jsonPath("$.itemType").isEqualTo("REVIEW")
                .jsonPath("$.dispositionStatus").isEqualTo("UNREAD")
                .jsonPath("$.etag").isEqualTo("\"0\"")
                .jsonPath("$.source.type").isEqualTo("REVIEW_REQUEST")
                .jsonPath("$.memberId").doesNotExist()
                .jsonPath("$.projectionGeneration").doesNotExist()
                .jsonPath("$.projectionName").doesNotExist();
    }

    @Test
    void countsAlwaysContainsAllFiveCategories() {
        when(queries.counts(access, ORGANIZATION_ID, TEAM_ID)).thenReturn(new InboxCounts(Map.of(
                InboxItemType.REVIEW, new InboxTypeCount(2, 1))));

        client.get()
                .uri(route("/counts"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.total").isEqualTo(2)
                .jsonPath("$.unread").isEqualTo(1)
                .jsonPath("$.byType.REVIEW.total").isEqualTo(2)
                .jsonPath("$.byType.OWNERSHIP.total").isEqualTo(0)
                .jsonPath("$.byType.EXCEPTION.unread").isEqualTo(0);
    }

    @Test
    void dispositionRequiresBothHeadersAndReturnsCommittedStrongEtag() {
        InboxDisposition disposition = mock(InboxDisposition.class);
        CommandReceipt receipt = new CommandReceipt(
                UUID.randomUUID(), UUID.randomUUID(), 1, UUID.randomUUID());
        when(commands.change(any(), eq(ORGANIZATION_ID), eq(TEAM_ID), eq(item.id()), any()))
                .thenReturn(CommandExecution.completed(disposition, receipt));

        client.put()
                .uri(route("/" + item.id() + "/disposition"))
                .header(ApiHeaders.IDEMPOTENCY_KEY, "m6-a02-read-missing-etag")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"status\":\"READ\"}")
                .exchange()
                .expectStatus().isEqualTo(428);

        client.put()
                .uri(route("/" + item.id() + "/disposition"))
                .header(ApiHeaders.IDEMPOTENCY_KEY, "m6-a02-read")
                .header(ApiHeaders.IF_MATCH, "\"0\"")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"status\":\"READ\"}")
                .exchange()
                .expectStatus().isAccepted()
                .expectHeader().valueEquals(ApiHeaders.ETAG, "\"1\"")
                .expectBody()
                .jsonPath("$.committedVersion").isEqualTo(1);
    }

    @Test
    void targetUsesOnlyTheServerOwnedInternalRouteTemplate() {
        InboxSourceTarget target = new InboxSourceTarget(
                InboxSourceTarget.Kind.REVIEW,
                TEAM_ID,
                Optional.of(WorkProjectId.generate()),
                Optional.of(WorkItemId.generate()),
                Optional.of(UUID.randomUUID()),
                Optional.of(UUID.randomUUID()),
                item.source().key().sourceId());
        when(queries.target(access, ORGANIZATION_ID, TEAM_ID, item.id())).thenReturn(target);

        client.get()
                .uri(route("/" + item.id() + "/target"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.kind").isEqualTo("REVIEW")
                .jsonPath("$.href").value(value -> {
                    String href = value.toString();
                    org.junit.jupiter.api.Assertions.assertTrue(href.startsWith("/work?"));
                    org.junit.jupiter.api.Assertions.assertFalse(href.contains("http"));
                    org.junit.jupiter.api.Assertions.assertTrue(href.contains("review="));
                });
    }

    @Test
    void expiredProjectionCursorUsesTheSharedGoneContract() {
        InboxFilter filter = InboxFilter.OPEN;
        String cursor = new InboxCursorCodec().encode(
                InboxCursor.from(InboxItemView.merge(item, Optional.empty())),
                ORGANIZATION_ID,
                TEAM_ID,
                filter);
        when(queries.list(
                        access,
                        ORGANIZATION_ID,
                        TEAM_ID,
                        filter,
                        Optional.of(InboxCursor.from(
                                InboxItemView.merge(item, Optional.empty()))),
                        ApiPagination.DEFAULT_LIMIT))
                .thenThrow(new InboxCursorExpiredException());

        client.get()
                .uri(route("") + "?after=" + cursor)
                .exchange()
                .expectStatus().isEqualTo(410)
                .expectBody()
                .jsonPath("$.code").isEqualTo("cursor_expired");
    }

    @Test
    void rejectsUnauthorizedCursorBeforeItsShapeIsDecoded() {
        doThrow(new PolicyDeniedException("access this Team's Inbox"))
                .when(queries)
                .requireAccess(access, ORGANIZATION_ID, TEAM_ID);

        client.get()
                .uri(route("") + "?after=not-a-signed-cursor")
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.code").isEqualTo("policy_denied");

        verify(queries, never()).list(any(), any(), any(), any(), any(), any(Integer.class));
    }

    @Test
    void dispositionBodyRejectsUnknownCommandFields() {
        client.put()
                .uri(route("/" + item.id() + "/disposition"))
                .header(ApiHeaders.IDEMPOTENCY_KEY, "m6-a02-closed-body")
                .header(ApiHeaders.IF_MATCH, "\"0\"")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"status\":\"READ\",\"memberId\":\"forged\"}")
                .exchange()
                .expectStatus().isBadRequest();

        verify(commands, never()).change(any(), any(), any(), any(), any());
    }

    private static InboxItem item() {
        InboxSource source = InboxSource.open(
                new InboxSourceKey(
                        ORGANIZATION_ID,
                        TeamMemberId.generate(),
                        InboxItemType.REVIEW,
                        InboxSourceType.REVIEW_REQUEST,
                        UUID.randomUUID(),
                        new InboxSourceRevision(1)),
                InboxPriority.HIGH,
                Optional.empty(),
                NOW);
        return InboxItem.project(
                TEAM_ID,
                new ProjectionName("member-inbox"),
                ProjectionGeneration.FIRST,
                SchemaVersion.V1,
                source);
    }

    private static String route(String suffix) {
        return "/api/v1/organizations/"
                + ORGANIZATION_ID
                + "/teams/"
                + TEAM_ID
                + "/inbox"
                + suffix;
    }
}
