package io.crewscope.server.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.application.collaboration.LarkMappingAdministration;
import io.crewscope.application.notification.NotificationAdministrationRepository;
import io.crewscope.application.notification.NotificationAdministrationService;
import io.crewscope.application.notification.NotificationPlanningApplicationService;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamMemberRepository;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.notification.NotificationPreference;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamJoinMethod;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.team.TeamScope;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/**
 * A07: the member's own preference is guarded by the active membership itself — an administrator
 * grant is never required here, and removed or foreign members are indistinguishably forbidden.
 */
class MemberSelfNotificationPreferenceControllerTest {

    private static final UtcTimestamp NOW =
            UtcTimestamp.from(Instant.parse("2026-09-25T08:00:00Z"));
    private static final OrganizationId ORGANIZATION_ID = OrganizationId.generate();
    private static final TeamId TEAM_ID = TeamId.generate();

    private NotificationAdministrationRepository repository;
    private TeamMemberRepository members;
    private WebTestClient client;
    private TeamMember membership;

    @BeforeEach
    void setUp() {
        repository = mock(NotificationAdministrationRepository.class);
        members = mock(TeamMemberRepository.class);
        NotificationAdministrationService notifications = new NotificationAdministrationService(
                mock(LarkMappingAdministration.class),
                repository,
                mock(NotificationPlanningApplicationService.class),
                members,
                () -> NOW);
        Principal actor = Principal.create(
                PrincipalId.generate(),
                PrincipalScope.organization(ORGANIZATION_ID),
                PrincipalType.USER,
                Optional.empty(),
                "Self Member",
                Optional.empty(),
                PrincipalVisibility.ORGANIZATION,
                NOW);
        membership = TeamMember.join(
                TeamMemberId.generate(),
                new TeamScope(ORGANIZATION_ID, TEAM_ID),
                actor,
                TeamJoinMethod.OIDC,
                NOW);
        TeamRequestIdentityResolver identities = mock(TeamRequestIdentityResolver.class);
        when(identities.resolve(any(), eq(ORGANIZATION_ID), any()))
                .thenReturn(Mono.just(new TeamAccessContext(actor, false)));
        client = WebTestClient.bindToController(new MemberSelfNotificationPreferenceController(
                        notifications, identities))
                .controllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void participatingMemberReadsTheDefaultPreferenceWithItsZeroVersionEtag() {
        when(members.findByTeamAndUserPrincipalId(ORGANIZATION_ID, TEAM_ID, membership.userPrincipalId()))
                .thenReturn(Optional.of(membership));
        when(repository.findPreference(ORGANIZATION_ID, TEAM_ID, membership.id()))
                .thenReturn(Optional.empty());

        client.get().uri(route())
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(ApiHeaders.ETAG, "\"0\"")
                .expectBody()
                .jsonPath("$.memberId").isEqualTo(membership.id().toString())
                .jsonPath("$.enabled").isEqualTo(true)
                .jsonPath("$.enabledItemTypes.size()").isEqualTo(
                        io.crewscope.domain.inbox.InboxItemType.values().length);
    }

    @Test
    void nonMemberAndRemovedMemberAreIndistinguishablyForbidden() {
        when(members.findByTeamAndUserPrincipalId(ORGANIZATION_ID, TEAM_ID, membership.userPrincipalId()))
                .thenReturn(Optional.empty());
        client.get().uri(route())
                .exchange()
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.code").isEqualTo("policy_denied");

        when(members.findByTeamAndUserPrincipalId(ORGANIZATION_ID, TEAM_ID, membership.userPrincipalId()))
                .thenReturn(Optional.of(membership.remove(NOW)));
        client.get().uri(route())
                .exchange()
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.code").isEqualTo("policy_denied");
    }

    @Test
    void updateSavesTheOwnMemberPreferenceAndReturnsTheNextVersionEtag() {
        when(members.findByTeamAndUserPrincipalId(ORGANIZATION_ID, TEAM_ID, membership.userPrincipalId()))
                .thenReturn(Optional.of(membership));
        NotificationPreference saved = new NotificationPreference(
                membership.id(), false, EnumSet.of(io.crewscope.domain.inbox.InboxItemType.EXECUTION),
                Optional.empty(), 4);
        when(repository.savePreference(
                eq(ORGANIZATION_ID), eq(TEAM_ID), any(), eq(3L), any(), eq(NOW)))
                .thenReturn(saved);

        client.put().uri(route())
                .header(ApiHeaders.IF_MATCH, "\"3\"")
                .header(ApiHeaders.IDEMPOTENCY_KEY, "self-preference-1")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"enabled":false,"enabledItemTypes":["EXECUTION"],"mutedUntil":""}
                        """)
                .exchange()
                .expectStatus().isAccepted()
                .expectHeader().valueEquals(ApiHeaders.ETAG, "\"4\"")
                .expectBody()
                .jsonPath("$.memberId").isEqualTo(membership.id().toString())
                .jsonPath("$.enabled").isEqualTo(false);

        ArgumentCaptor<NotificationPreference> preference =
                ArgumentCaptor.forClass(NotificationPreference.class);
        org.mockito.Mockito.verify(repository).savePreference(
                eq(ORGANIZATION_ID), eq(TEAM_ID), preference.capture(), eq(3L), any(), eq(NOW));
        if (preference.getValue().enabled() || preference.getValue().version() != 4) {
            throw new AssertionError("saved preference must carry the requested shape");
        }
    }

    @Test
    void versionConflictAndMissingPreconditionsKeepTheStrongVersionContract() {
        when(members.findByTeamAndUserPrincipalId(ORGANIZATION_ID, TEAM_ID, membership.userPrincipalId()))
                .thenReturn(Optional.of(membership));
        when(repository.savePreference(
                eq(ORGANIZATION_ID), eq(TEAM_ID), any(), eq(2L), any(), eq(NOW)))
                .thenThrow(new OptimisticLockConflictException(
                        "NotificationPreference", membership.id(), 2, 5));

        client.put().uri(route())
                .header(ApiHeaders.IF_MATCH, "\"2\"")
                .header(ApiHeaders.IDEMPOTENCY_KEY, "self-preference-conflict")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"enabled":true,"enabledItemTypes":["EXECUTION","REVIEW"],"mutedUntil":""}
                        """)
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.code").isEqualTo("optimistic_lock_conflict")
                .jsonPath("$.details.expectedVersion").isEqualTo("2")
                .jsonPath("$.details.actualVersion").isEqualTo("5");

        client.put().uri(route())
                .header(ApiHeaders.IDEMPOTENCY_KEY, "self-preference-no-if-match")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"enabled":true,"enabledItemTypes":["EXECUTION"],"mutedUntil":""}
                        """)
                .exchange()
                .expectStatus().isEqualTo(428)
                .expectBody().jsonPath("$.code").isEqualTo("precondition_required");

        client.put().uri(route())
                .header(ApiHeaders.IF_MATCH, "\"0\"")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"enabled":true,"enabledItemTypes":["EXECUTION"],"mutedUntil":""}
                        """)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("invalid_request");
    }

    private static String route() {
        return "/api/v1/organizations/" + ORGANIZATION_ID + "/teams/" + TEAM_ID
                + "/members/me/notification-preference";
    }
}
