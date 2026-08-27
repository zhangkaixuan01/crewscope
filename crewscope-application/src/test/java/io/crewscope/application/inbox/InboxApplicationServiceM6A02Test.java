package io.crewscope.application.inbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.command.CommandReservation;
import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.domain.inbox.InboxDisposition;
import io.crewscope.domain.inbox.InboxDispositionStatus;
import io.crewscope.domain.inbox.InboxItem;
import io.crewscope.domain.inbox.InboxItemId;
import io.crewscope.domain.inbox.InboxItemType;
import io.crewscope.domain.inbox.InboxPriority;
import io.crewscope.domain.inbox.InboxSource;
import io.crewscope.domain.inbox.InboxSourceKey;
import io.crewscope.domain.inbox.InboxSourceRevision;
import io.crewscope.domain.inbox.InboxSourceType;
import io.crewscope.domain.projection.ProjectionGeneration;
import io.crewscope.domain.projection.ProjectionName;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.event.SchemaVersion;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkProjectId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Member isolation, safe target authorization and idempotent disposition proof for M6-A02. */
class InboxApplicationServiceM6A02Test {

    private static final OrganizationId ORGANIZATION_ID = OrganizationId.generate();
    private static final TeamId TEAM_ID = TeamId.generate();
    private static final TeamMemberId MEMBER_ID = TeamMemberId.generate();
    private static final TeamMemberId OTHER_MEMBER_ID = TeamMemberId.generate();
    private static final WorkProjectId PROJECT_ID = WorkProjectId.generate();
    private static final WorkItemId WORK_ITEM_ID = WorkItemId.generate();
    private static final UtcTimestamp NOW =
            UtcTimestamp.from(Instant.parse("2026-08-27T01:00:00Z"));

    private InboxItemQueryPort queries;
    private WorkItemAccessPolicy accessPolicy;
    private TeamAccessContext access;
    private InboxApplicationService service;

    @BeforeEach
    void setUp() {
        queries = mock(InboxItemQueryPort.class);
        accessPolicy = mock(WorkItemAccessPolicy.class);
        access = mock(TeamAccessContext.class);
        TeamMember member = mock(TeamMember.class);
        when(member.id()).thenReturn(MEMBER_ID);
        when(accessPolicy.requireVisibleTeamMember(access, ORGANIZATION_ID, TEAM_ID))
                .thenReturn(member);
        service = new InboxApplicationService(queries, accessPolicy);
    }

    @Test
    void listAlwaysUsesTheCurrentActorsMemberIdentity() {
        InboxPage page = new InboxPage(List.of(), Optional.empty());
        when(queries.findCurrentPage(any())).thenReturn(page);

        assertEquals(
                page,
                service.list(
                        access,
                        ORGANIZATION_ID,
                        TEAM_ID,
                        new InboxFilter(
                                Set.of(InboxItemType.REVIEW), Set.of(), Set.of()),
                        Optional.empty(),
                        25));
        verify(queries).findCurrentPage(new InboxQuery(
                ORGANIZATION_ID,
                TEAM_ID,
                MEMBER_ID,
                new InboxFilter(Set.of(InboxItemType.REVIEW), Set.of(), Set.of()),
                Optional.empty(),
                25));
    }

    @Test
    void anotherMembersItemIsIndistinguishableFromMissing() {
        InboxItem foreign = item(OTHER_MEMBER_ID);
        when(queries.findCurrentView(ORGANIZATION_ID, TEAM_ID, foreign.id()))
                .thenReturn(Optional.of(InboxItemView.merge(foreign, Optional.empty())));

        assertThrows(
                AggregateNotFoundException.class,
                () -> service.detail(access, ORGANIZATION_ID, TEAM_ID, foreign.id()));
    }

    @Test
    void sourceTargetIsServerResolvedAndWorkItemAccessIsRechecked() {
        InboxItem own = item(MEMBER_ID);
        InboxSourceTarget target = new InboxSourceTarget(
                InboxSourceTarget.Kind.WORK_ITEM,
                TEAM_ID,
                Optional.of(PROJECT_ID),
                Optional.of(WORK_ITEM_ID),
                Optional.empty(),
                Optional.empty(),
                own.source().key().sourceId());
        when(queries.findCurrentView(ORGANIZATION_ID, TEAM_ID, own.id()))
                .thenReturn(Optional.of(InboxItemView.merge(own, Optional.empty())));
        when(queries.resolveCurrentTarget(ORGANIZATION_ID, TEAM_ID, MEMBER_ID, own.id()))
                .thenReturn(Optional.of(target));

        assertEquals(target, service.target(access, ORGANIZATION_ID, TEAM_ID, own.id()));
        verify(accessPolicy).requireVisibleWorkItem(
                access, ORGANIZATION_ID, TEAM_ID, PROJECT_ID, WORK_ITEM_ID);
    }

    @Test
    void dispositionIdempotencyReplaysTheCommittedReceiptWithoutASecondMutation() {
        InboxDispositionApplicationService dispositionService =
                mock(InboxDispositionApplicationService.class);
        InboxApplicationService authorizationQueries = mock(InboxApplicationService.class);
        CommandReceiptStore receiptStore = mock(CommandReceiptStore.class);
        TransactionExecutor direct = new TransactionExecutor() {
            @Override
            public <T> T required(Supplier<T> operation) {
                return operation.get();
            }
        };
        InboxDisposition disposition = mock(InboxDisposition.class);
        when(disposition.version()).thenReturn(1L);
        when(dispositionService.change(any(), any(), any(), any(), any()))
                .thenReturn(disposition);
        when(receiptStore.findCompleted(any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(receiptStore.reserve(any())).thenReturn(CommandReservation.newlyAcquired());
        InboxDispositionCommandService commandService = new InboxDispositionCommandService(
                authorizationQueries, dispositionService, receiptStore, direct, () -> NOW);
        TeamCommandContext commandContext = new TeamCommandContext(
                access,
                IdempotencyKey.from("m6-a02-read-1"),
                UUID.randomUUID(),
                Optional.empty());
        InboxItemId itemId = item(MEMBER_ID).id();
        ChangeInboxDispositionCommand command = new ChangeInboxDispositionCommand(
                InboxDispositionStatus.READ, 0);

        var first = commandService.change(
                commandContext, ORGANIZATION_ID, TEAM_ID, itemId, command);
        CommandReceipt receipt = first.receipt();
        when(receiptStore.findCompleted(any(), any(), any(), any()))
                .thenReturn(Optional.of(receipt));
        var replay = commandService.change(
                commandContext, ORGANIZATION_ID, TEAM_ID, itemId, command);

        assertEquals(receipt, replay.receipt());
        assertEquals(true, replay.replayed());
        verify(dispositionService).change(
                eq(access), eq(ORGANIZATION_ID), eq(TEAM_ID), eq(itemId), eq(command));
        verify(authorizationQueries, org.mockito.Mockito.times(2)).detail(
                access, ORGANIZATION_ID, TEAM_ID, itemId);
    }

    @Test
    void completedReceiptIsNotRevealedWhenCurrentAuthorizationFails() {
        InboxApplicationService authorizationQueries = mock(InboxApplicationService.class);
        InboxDispositionApplicationService dispositionService =
                mock(InboxDispositionApplicationService.class);
        CommandReceiptStore receiptStore = mock(CommandReceiptStore.class);
        TransactionExecutor transactions = mock(TransactionExecutor.class);
        InboxItemId itemId = item(MEMBER_ID).id();
        when(authorizationQueries.detail(access, ORGANIZATION_ID, TEAM_ID, itemId))
                .thenThrow(new AggregateNotFoundException("InboxItem", itemId));
        InboxDispositionCommandService commandService = new InboxDispositionCommandService(
                authorizationQueries,
                dispositionService,
                receiptStore,
                transactions,
                () -> NOW);
        TeamCommandContext commandContext = new TeamCommandContext(
                access,
                IdempotencyKey.from("m6-a02-departed-member-replay"),
                UUID.randomUUID(),
                Optional.empty());
        ChangeInboxDispositionCommand command = new ChangeInboxDispositionCommand(
                InboxDispositionStatus.READ, 0);

        assertThrows(
                AggregateNotFoundException.class,
                () -> commandService.change(
                        commandContext, ORGANIZATION_ID, TEAM_ID, itemId, command));
        verify(receiptStore, never()).findCompleted(any(), any(), any(), any());
        verify(dispositionService, never()).change(any(), any(), any(), any(), any());
    }

    private static InboxItem item(TeamMemberId memberId) {
        InboxSource source = InboxSource.open(
                new InboxSourceKey(
                        ORGANIZATION_ID,
                        memberId,
                        InboxItemType.OWNERSHIP,
                        InboxSourceType.RESPONSIBILITY_ASSIGNMENT,
                        UUID.randomUUID(),
                        InboxSourceRevision.INITIAL),
                InboxPriority.NORMAL,
                Optional.empty(),
                NOW);
        return InboxItem.project(
                TEAM_ID,
                new ProjectionName("member-inbox"),
                ProjectionGeneration.FIRST,
                SchemaVersion.V1,
                source);
    }
}
