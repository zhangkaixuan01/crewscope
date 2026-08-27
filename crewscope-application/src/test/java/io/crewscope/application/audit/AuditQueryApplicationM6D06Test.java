package io.crewscope.application.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.team.MemberRoleRepository;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.team.TeamRoleRepository;
import io.crewscope.domain.audit.AuditCorrelationReference;
import io.crewscope.domain.audit.AuditEventCategory;
import io.crewscope.domain.audit.AuditEventId;
import io.crewscope.domain.audit.AuditIdentityChain;
import io.crewscope.domain.audit.AuditOutcome;
import io.crewscope.domain.audit.AuditQueryEvent;
import io.crewscope.domain.audit.AuditRetentionLevel;
import io.crewscope.domain.audit.AuditSummarySchema;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.event.AggregateReference;
import io.crewscope.domain.shared.event.EventActor;
import io.crewscope.domain.shared.event.EventActorType;
import io.crewscope.domain.shared.event.EventType;
import io.crewscope.domain.shared.event.SchemaVersion;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.BuiltInTeamRole;
import io.crewscope.domain.team.MemberRole;
import io.crewscope.domain.team.MemberRoleId;
import io.crewscope.domain.team.RoleScope;
import io.crewscope.domain.team.TeamInitialization;
import io.crewscope.domain.team.TeamRole;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** M6-D06 Audit keyset, authorization and bounded-export application tests. */
class AuditQueryApplicationM6D06Test {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-25T12:00:00Z");

    private final OrganizationId organizationId = OrganizationId.generate();
    private final Principal actor = Principal.create(
            PrincipalId.generate(),
            PrincipalScope.organization(organizationId),
            PrincipalType.USER,
            Optional.empty(),
            "Audit owner",
            Optional.empty(),
            PrincipalVisibility.ORGANIZATION,
            NOW);
    private final TeamInitialization initialization =
            TeamInitialization.create(actor, "Audit Team", NOW);

    private TeamMembershipQuery memberships;
    private TeamRoleRepository roles;
    private MemberRoleRepository grants;
    private AuditQueryPort port;
    private AuditQueryApplicationService service;

    @BeforeEach
    void setUp() {
        memberships = mock(TeamMembershipQuery.class);
        roles = mock(TeamRoleRepository.class);
        grants = mock(MemberRoleRepository.class);
        port = mock(AuditQueryPort.class);
        when(memberships.findByTeam(organizationId, initialization.team().id()))
                .thenReturn(List.of(initialization.ownerMember()));
        when(roles.findByTeam(organizationId, initialization.team().id()))
                .thenReturn(initialization.builtInRoles());
        when(grants.findByMember(organizationId, initialization.ownerMember().id()))
                .thenReturn(List.of(initialization.ownerRole()));
        service = new AuditQueryApplicationService(
                port,
                new DefaultAuditAuthorization(memberships, roles, grants),
                AuditAccessRecorder.noOp(),
                () -> NOW);
    }

    @Test
    void queryUsesDeterministicFilterScopeAndStrictNewestFirstKeyset() {
        AuditQueryFilter firstFilter = filter(
                new LinkedHashSet<>(List.of(AuditEventCategory.SECURITY, AuditEventCategory.WORK)),
                Set.of(AuditOutcome.SUCCEEDED));
        AuditQueryFilter reorderedFilter = filter(
                new LinkedHashSet<>(List.of(AuditEventCategory.WORK, AuditEventCategory.SECURITY)),
                Set.of(AuditOutcome.SUCCEEDED));
        assertEquals(firstFilter.fingerprint(), reorderedFilter.fingerprint());
        AuditQuery query = AuditQuery.create(
                organizationId,
                initialization.team().id(),
                firstFilter,
                Optional.empty(),
                20);
        AuditQueryEvent newest = event(new UUID(0, 3), NOW);
        AuditQueryEvent older = event(
                new UUID(0, 2), UtcTimestamp.parse("2026-08-25T11:59:59Z"));
        AuditPage expected = new AuditPage(query, List.of(newest, older), true);
        when(port.find(query)).thenReturn(expected);

        AuditPage result = service.query(access(), query);

        assertEquals(expected, result);
        assertEquals(older.id(), result.nextCursor().orElseThrow().eventId());
        verify(port).find(query);
    }

    @Test
    void cursorCannotCrossTeamOrFilterAndPageRejectsWrongOrder() {
        AuditQueryFilter filter = filter(
                Set.of(AuditEventCategory.WORK), Set.of(AuditOutcome.SUCCEEDED));
        AuditQuery first = AuditQuery.create(
                organizationId,
                initialization.team().id(),
                filter,
                Optional.empty(),
                20);
        AuditQueryEvent newest = event(new UUID(0, 3), NOW);
        AuditCursor cursor = AuditCursor.from(first.cursorScope(), newest);

        assertThrows(
                IllegalArgumentException.class,
                () -> AuditQuery.create(
                        organizationId,
                        TeamId.generate(),
                        filter,
                        Optional.of(cursor),
                        20));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AuditPage(
                        first,
                        List.of(
                                event(new UUID(0, 2), UtcTimestamp.parse("2026-08-25T11:00:00Z")),
                                newest),
                        false));
    }

    @Test
    void uuidTieBreakerUsesTheSameUnsignedOrderAsPostgres() {
        AuditQuery query = AuditQuery.create(
                organizationId,
                initialization.team().id(),
                filter(Set.of(AuditEventCategory.WORK), Set.of(AuditOutcome.SUCCEEDED)),
                Optional.empty(),
                20);
        AuditQueryEvent unsignedLarger = event(
                new UUID(Long.MIN_VALUE, 0), NOW);
        AuditQueryEvent unsignedSmaller = event(
                new UUID(Long.MAX_VALUE, Long.MAX_VALUE), NOW);

        AuditPage page = new AuditPage(
                query, List.of(unsignedLarger, unsignedSmaller), false);

        assertEquals(List.of(unsignedLarger, unsignedSmaller), page.events());
    }

    @Test
    void ownerWithGovernancePermissionCanRunBoundedExport() {
        AuditExportRequest request = exportRequest(1_000, 24);
        AuditExportBatch expected = new AuditExportBatch(
                request,
                NOW,
                List.of(event(
                        new UUID(0, 1), UtcTimestamp.parse("2026-08-25T11:59:59Z"))));
        when(port.export(request)).thenReturn(expected);

        AuditExportBatch result = service.export(access(), request);

        assertEquals(expected, result);
        verify(port).export(request);
    }

    @Test
    void teamAdminCanReadButCannotExportWithoutGovernanceExportGrant() {
        TeamRole teamAdmin = initialization.builtInRoles().stream()
                .filter(role -> role.isBuiltIn(BuiltInTeamRole.TEAM_ADMIN))
                .findFirst()
                .orElseThrow();
        MemberRole teamAdminGrant = MemberRole.grant(
                MemberRoleId.generate(),
                initialization.ownerMember(),
                teamAdmin,
                RoleScope.team(),
                actor.id(),
                NOW,
                NOW,
                Optional.empty());
        when(grants.findByMember(organizationId, initialization.ownerMember().id()))
                .thenReturn(List.of(teamAdminGrant));
        AuditQuery query = AuditQuery.create(
                organizationId,
                initialization.team().id(),
                AuditQueryFilter.ALL,
                Optional.empty(),
                20);
        when(port.find(query)).thenReturn(new AuditPage(query, List.of(), false));

        assertEquals(List.of(), service.query(access(), query).events());
        assertThrows(
                PolicyDeniedException.class,
                () -> service.export(access(), exportRequest(100, 1)));
        verify(port, never()).export(any());
    }

    @Test
    void revokedPermissionStopsReadBeforeRepositoryAccess() {
        when(grants.findByMember(organizationId, initialization.ownerMember().id()))
                .thenReturn(List.of());
        AuditQuery query = AuditQuery.create(
                organizationId,
                initialization.team().id(),
                AuditQueryFilter.ALL,
                Optional.empty(),
                20);

        assertThrows(PolicyDeniedException.class, () -> service.query(access(), query));

        verify(port, never()).find(any());
    }

    @Test
    void platformAdministratorCanReadWithoutATeamMembershipGrant() {
        when(memberships.findByTeam(organizationId, initialization.team().id()))
                .thenReturn(List.of());
        AuditQuery query = AuditQuery.create(
                organizationId,
                initialization.team().id(),
                AuditQueryFilter.ALL,
                Optional.empty(),
                20);
        AuditPage expected = new AuditPage(query, List.of(), false);
        when(port.find(query)).thenReturn(expected);

        AuditPage result = service.query(new TeamAccessContext(actor, true), query);

        assertEquals(expected, result);
        verify(port).find(query);
    }

    @Test
    void exportRequiresExplicitShortTimeRangeAndHardRowLimit() {
        assertThrows(
                DomainValidationException.class,
                () -> AuditExportRequest.create(
                        organizationId,
                        initialization.team().id(),
                        AuditQueryFilter.ALL,
                        100));
        assertThrows(
                DomainValidationException.class,
                () -> exportRequest(AuditExportRequest.MAXIMUM_ROWS + 1, 1));
        assertThrows(
                DomainValidationException.class,
                () -> exportRequest(100, 32 * 24));
    }

    private AuditQueryFilter filter(
            Set<AuditEventCategory> categories, Set<AuditOutcome> outcomes) {
        return new AuditQueryFilter(
                Optional.empty(),
                Optional.empty(),
                categories,
                outcomes,
                Set.of(),
                Set.of(),
                Set.of(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    private AuditExportRequest exportRequest(int maximumRows, int hours) {
        AuditQueryFilter filter = new AuditQueryFilter(
                Optional.of(UtcTimestamp.parse("2026-08-24T12:00:00Z")),
                Optional.of(UtcTimestamp.from(
                        UtcTimestamp.parse("2026-08-24T12:00:00Z").value().plusSeconds(hours * 3600L))),
                Set.of(AuditEventCategory.WORK),
                Set.of(AuditOutcome.SUCCEEDED),
                Set.of(),
                Set.of(),
                Set.of(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
        return AuditExportRequest.create(
                organizationId, initialization.team().id(), filter, maximumRows);
    }

    private AuditQueryEvent event(UUID id, UtcTimestamp occurredAt) {
        AuditSummarySchema schema = new AuditSummarySchema(
                EventType.from("WORK_ITEM_CREATED"),
                SchemaVersion.V1,
                AuditEventCategory.WORK,
                Set.of("action"),
                Set.of());
        return new AuditQueryEvent(
                new AuditEventId(id),
                organizationId,
                initialization.team().id(),
                AuditEventCategory.WORK,
                AuditOutcome.SUCCEEDED,
                AuditIdentityChain.from(
                        Optional.of(actor.id()),
                        EventActor.principal(EventActorType.USER, actor.id())),
                new AggregateReference("WORK_ITEM", UUID.randomUUID()),
                Optional.empty(),
                new AuditCorrelationReference(
                        UUID.randomUUID(), Optional.empty(), Optional.empty()),
                AuditRetentionLevel.STANDARD,
                occurredAt,
                schema.project(Map.of("action", "Work item created")));
    }

    private TeamAccessContext access() {
        return new TeamAccessContext(actor, false);
    }
}
