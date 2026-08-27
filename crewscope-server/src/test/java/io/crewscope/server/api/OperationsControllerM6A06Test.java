package io.crewscope.server.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.operations.NotificationDeliveryRecoveryTarget;
import io.crewscope.application.operations.OperationsAdministratorDiagnostics;
import io.crewscope.application.operations.OperationsComponentSummary;
import io.crewscope.application.operations.OperationsHealthComponent;
import io.crewscope.application.operations.OperationsHealthLevel;
import io.crewscope.application.operations.OperationsHealthService;
import io.crewscope.application.operations.OperationsMemberHealthSummary;
import io.crewscope.application.operations.OperationsRecoveryCommand;
import io.crewscope.application.operations.OperationsRecoveryResult;
import io.crewscope.application.operations.OperationsRecoveryService;
import io.crewscope.application.operations.OperationsRecoveryStatus;
import io.crewscope.application.operations.ProjectionHealthDiagnostic;
import io.crewscope.application.projection.ProjectionAdministrationResult;
import io.crewscope.application.projection.ProjectionAdministrationService;
import io.crewscope.application.projection.RetryProjectionRebuildCommand;
import io.crewscope.application.projection.StartProjectionRebuildCommand;
import io.crewscope.application.projection.SwitchProjectionGenerationCommand;
import io.crewscope.application.projection.TerminateProjectionRebuildCommand;
import io.crewscope.application.projection.ValidateProjectionGenerationCommand;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.notification.NotificationDeliveryId;
import io.crewscope.domain.projection.ProjectionDefinitionVersion;
import io.crewscope.domain.projection.ProjectionGeneration;
import io.crewscope.domain.projection.ProjectionGenerationStatus;
import io.crewscope.domain.projection.ProjectionName;
import io.crewscope.domain.projection.ProjectionRebuildJobId;
import io.crewscope.domain.projection.ProjectionRebuildStatus;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/** Security, DTO and command-shape acceptance tests for the M6-A06 operations boundary. */
class OperationsControllerM6A06Test {

    private static final OrganizationId ORGANIZATION_ID = OrganizationId.generate();
    private static final TeamId TEAM_ID = TeamId.generate();
    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-27T06:00:00Z");
    private static final ProjectionName PROJECTION = new ProjectionName("team-activity");

    private OperationsHealthService health;
    private OperationsRecoveryService recovery;
    private ProjectionAdministrationService projections;
    private TeamAccessContext access;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        Principal actor = Principal.create(
                PrincipalId.generate(),
                PrincipalScope.organization(ORGANIZATION_ID),
                PrincipalType.USER,
                Optional.empty(),
                "Administrator",
                Optional.empty(),
                PrincipalVisibility.ORGANIZATION,
                NOW);
        access = new TeamAccessContext(actor, true);
        health = mock(OperationsHealthService.class);
        recovery = mock(OperationsRecoveryService.class);
        projections = mock(ProjectionAdministrationService.class);
        TeamRequestIdentityResolver identities = mock(TeamRequestIdentityResolver.class);
        when(identities.resolve(any(), eq(ORGANIZATION_ID), any()))
                .thenReturn(Mono.just(access));
        client = WebTestClient.bindToController(
                        new OperationsController(health, recovery, projections, identities))
                .controllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void memberHealthContainsOnlyIdentifierFreeLowCardinalitySummary() {
        when(health.summary(access, ORGANIZATION_ID, TEAM_ID)).thenReturn(summary());

        client.get()
                .uri(teamRoute("/health"))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().cacheControl(CacheControl.noStore())
                .expectBody()
                .jsonPath("$.health").isEqualTo("DEGRADED")
                .jsonPath("$.components.length()").isEqualTo(5)
                .jsonPath("$.components[0].component").isEqualTo("PROJECTION")
                .jsonPath("$.organizationId").doesNotExist()
                .jsonPath("$.teamId").doesNotExist()
                .jsonPath("$.projectionName").doesNotExist()
                .jsonPath("$.generation").doesNotExist()
                .jsonPath("$.payload").doesNotExist();
    }

    @Test
    void administratorDiagnosticsExposeOnlyExactSafeCoordinatesAndConfirmations() {
        NotificationDeliveryRecoveryTarget candidate = new NotificationDeliveryRecoveryTarget(
                new NotificationDeliveryId(UUID.randomUUID()), 4);
        ProjectionHealthDiagnostic projection = new ProjectionHealthDiagnostic(
                PROJECTION,
                ProjectionDefinitionVersion.V1,
                ProjectionGeneration.FIRST,
                3,
                5,
                Optional.of(new ProjectionGeneration(2)),
                Optional.of(ProjectionGenerationStatus.BUILDING),
                OptionalLong.of(1),
                Optional.of(ProjectionRebuildJobId.generate()),
                OptionalLong.of(2),
                7,
                0,
                1,
                Optional.empty());
        when(health.diagnostics(ORGANIZATION_ID, access))
                .thenReturn(new OperationsAdministratorDiagnostics(
                        summary(), List.of(projection), List.of(candidate)));

        client.get()
                .uri(organizationRoute("/diagnostics"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.projections[0].projectionName").isEqualTo("team-activity")
                .jsonPath("$.projections[0].shadowGeneration").isEqualTo(2)
                .jsonPath("$.projections[0].switchConfirmation")
                        .isEqualTo("CONFIRM_SWITCH_GENERATION:team-activity:2")
                .jsonPath("$.recoveryCandidates[0].type")
                        .isEqualTo("NOTIFICATION_DELIVERY")
                .jsonPath("$.recoveryCandidates[0].expectedVersion").isEqualTo(4)
                .jsonPath("$.recoveryCandidates[0].confirmation")
                        .isEqualTo("CONFIRM_RETRY_NOTIFICATION_DELIVERY:"
                                + candidate.deliveryId() + ":4")
                .jsonPath("$.payload").doesNotExist()
                .jsonPath("$.credential").doesNotExist()
                .jsonPath("$.exception").doesNotExist();
    }

    @Test
    void administratorAuthorizationFailureIsMappedToForbidden() {
        when(health.diagnostics(ORGANIZATION_ID, access))
                .thenThrow(new PolicyDeniedException("inspect operations diagnostics"));

        client.get()
                .uri(organizationRoute("/diagnostics"))
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.code").isEqualTo("policy_denied");
    }

    @Test
    void notificationRecoveryRequiresExactConfirmationAndReusesStableCommandIdentity() {
        NotificationDeliveryId deliveryId =
                new NotificationDeliveryId(UUID.randomUUID());
        when(recovery.recover(any())).thenAnswer(invocation -> {
            OperationsRecoveryCommand command = invocation.getArgument(0);
            return new OperationsRecoveryResult(
                    command.target().action(),
                    command.target().referenceHash(),
                    OperationsRecoveryStatus.SCHEDULED,
                    NOW);
        });
        String body = """
                {
                  "target": {
                    "type": "NOTIFICATION_DELIVERY",
                    "deliveryId": "%s",
                    "expectedVersion": 6
                  },
                  "confirmation": "CONFIRM_RETRY_NOTIFICATION_DELIVERY:%s:6"
                }
                """.formatted(deliveryId, deliveryId);

        for (int index = 0; index < 2; index++) {
            client.post()
                    .uri(organizationRoute("/recoveries"))
                    .header(ApiHeaders.IDEMPOTENCY_KEY, "retry-delivery-6")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .exchange()
                    .expectStatus().isAccepted()
                    .expectBody()
                    .jsonPath("$.action").isEqualTo("RETRY_NOTIFICATION_DELIVERY")
                    .jsonPath("$.status").isEqualTo("SCHEDULED")
                    .jsonPath("$.targetReferenceHash").value(value ->
                            assertTrue(value.toString().matches("[0-9a-f]{64}")));
        }

        ArgumentCaptor<OperationsRecoveryCommand> commands =
                ArgumentCaptor.forClass(OperationsRecoveryCommand.class);
        verify(recovery, times(2)).recover(commands.capture());
        assertEquals(
                commands.getAllValues().get(0).commandId(),
                commands.getAllValues().get(1).commandId());

        client.post()
                .uri(organizationRoute("/recoveries"))
                .header(ApiHeaders.IDEMPOTENCY_KEY, "wrong-confirmation")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body.replace(":6\"", ":7\""))
                .exchange()
                .expectStatus().isBadRequest();
        verify(recovery, times(2)).recover(any());
    }

    @Test
    void recoveryUnionRejectsExtraneousAndUnknownControlFields() {
        client.post()
                .uri(organizationRoute("/recoveries"))
                .header(ApiHeaders.IDEMPOTENCY_KEY, "closed-union")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "target": {
                            "type": "NOTIFICATION_DELIVERY",
                            "deliveryId": "00000000-0000-0000-0000-000000000001",
                            "expectedVersion": 1,
                            "domainEventId": "00000000-0000-0000-0000-000000000002"
                          },
                          "confirmation": "unused",
                          "sql": "DELETE FROM anything"
                        }
                        """)
                .exchange()
                .expectStatus().isBadRequest();

        verify(recovery, never()).recover(any());
    }

    @Test
    void outboxAndProjectionDeadLetterBodiesMapOnlyTheirExactCoordinates() {
        when(recovery.recover(any())).thenAnswer(invocation -> {
            OperationsRecoveryCommand command = invocation.getArgument(0);
            return new OperationsRecoveryResult(
                    command.target().action(),
                    command.target().referenceHash(),
                    OperationsRecoveryStatus.SCHEDULED,
                    NOW);
        });
        UUID outboxId = UUID.randomUUID();
        UUID outboxDomainEventId = UUID.randomUUID();
        client.post()
                .uri(organizationRoute("/recoveries"))
                .header(ApiHeaders.IDEMPOTENCY_KEY, "replay-outbox")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "target": {
                            "type": "OUTBOX_DEAD_LETTER",
                            "outboxEventId": "%s",
                            "domainEventId": "%s",
                            "expectedVersion": 2
                          },
                          "confirmation": "CONFIRM_REPLAY_OUTBOX_DEAD_LETTER:%s:2"
                        }
                        """.formatted(outboxId, outboxDomainEventId, outboxId))
                .exchange()
                .expectStatus().isAccepted()
                .expectBody()
                .jsonPath("$.action").isEqualTo("REPLAY_OUTBOX_DEAD_LETTER");

        UUID deadLetterId = UUID.randomUUID();
        UUID projectionDomainEventId = UUID.randomUUID();
        client.post()
                .uri(organizationRoute("/recoveries"))
                .header(ApiHeaders.IDEMPOTENCY_KEY, "replay-projection")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "target": {
                            "type": "PROJECTION_DEAD_LETTER",
                            "projectionName": "team-activity",
                            "generation": 2,
                            "deadLetterId": "%s",
                            "domainEventId": "%s",
                            "expectedGenerationVersion": 3
                          },
                          "confirmation": "CONFIRM_REPLAY_PROJECTION_DEAD_LETTER:team-activity:2:%s:3"
                        }
                        """.formatted(
                                deadLetterId, projectionDomainEventId, deadLetterId))
                .exchange()
                .expectStatus().isAccepted()
                .expectBody()
                .jsonPath("$.action").isEqualTo("REPLAY_PROJECTION_DEAD_LETTER");

        verify(recovery, times(2)).recover(any());
    }

    @Test
    void startAndSwitchMapExactVersionsAndStrongConfirmationToTypedCommands() {
        ProjectionRebuildJobId jobId = ProjectionRebuildJobId.generate();
        when(projections.start(any())).thenReturn(result(
                new ProjectionGeneration(2), jobId,
                ProjectionGenerationStatus.BUILDING,
                ProjectionRebuildStatus.BUILDING,
                OptionalLong.empty()));
        client.post()
                .uri(organizationRoute("/projections/team-activity/rebuilds"))
                .header(ApiHeaders.IDEMPOTENCY_KEY, "start-activity-v2")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "expectedDefinitionVersion": 1,
                          "expectedPointerVersion": 3,
                          "confirmation": "CONFIRM_START_REBUILD:team-activity"
                        }
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.generation").isEqualTo(2)
                .jsonPath("$.generationVersion").isEqualTo(0)
                .jsonPath("$.rebuildJobVersion").isEqualTo(0);

        ArgumentCaptor<StartProjectionRebuildCommand> start =
                ArgumentCaptor.forClass(StartProjectionRebuildCommand.class);
        verify(projections).start(start.capture());
        assertEquals(3, start.getValue().expectedPointerVersion());

        when(projections.switchGeneration(any())).thenReturn(result(
                new ProjectionGeneration(2), jobId,
                ProjectionGenerationStatus.ACTIVE,
                ProjectionRebuildStatus.COMPLETED,
                OptionalLong.of(4)));
        client.post()
                .uri(organizationRoute(
                        "/projections/team-activity/generations/2/switch"))
                .header(ApiHeaders.IDEMPOTENCY_KEY, "switch-activity-v2")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "expectedDefinitionVersion": 1,
                          "previousActiveGeneration": 1,
                          "rebuildJobId": "%s",
                          "expectedPointerVersion": 3,
                          "expectedPreviousGenerationVersion": 5,
                          "expectedTargetGenerationVersion": 2,
                          "expectedJobVersion": 3,
                          "confirmation": "CONFIRM_SWITCH_GENERATION:team-activity:2"
                        }
                        """.formatted(jobId.value()))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.pointerVersion").isEqualTo(4)
                .jsonPath("$.generationVersion").isEqualTo(3)
                .jsonPath("$.rebuildJobVersion").isEqualTo(4);

        ArgumentCaptor<SwitchProjectionGenerationCommand> switched =
                ArgumentCaptor.forClass(SwitchProjectionGenerationCommand.class);
        verify(projections).switchGeneration(switched.capture());
        assertEquals(5, switched.getValue().expectedPreviousGenerationVersion());
        assertEquals(2, switched.getValue().expectedTargetGenerationVersion());
        assertEquals(3, switched.getValue().expectedJobVersion());
    }

    @Test
    void projectionRequestsRejectUnknownFieldsAndWrongGenerationConfirmation() {
        client.post()
                .uri(organizationRoute("/projections/team-activity/rebuilds"))
                .header(ApiHeaders.IDEMPOTENCY_KEY, "unsafe-start")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "expectedDefinitionVersion": 1,
                          "expectedPointerVersion": 0,
                          "confirmation": "CONFIRM_START_REBUILD:another-projection",
                          "table": "projection_pointer"
                        }
                        """)
                .exchange()
                .expectStatus().isBadRequest();

        verify(projections, never()).start(any());
    }

    @Test
    void projectionVersionConflictReturnsStableConflictEnvelope() {
        when(projections.start(any())).thenThrow(new OptimisticLockConflictException(
                "ProjectionPointer", ORGANIZATION_ID + "/" + PROJECTION, 3, 5));

        client.post()
                .uri(organizationRoute("/projections/team-activity/rebuilds"))
                .header(ApiHeaders.IDEMPOTENCY_KEY, "stale-pointer")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "expectedDefinitionVersion": 1,
                          "expectedPointerVersion": 3,
                          "confirmation": "CONFIRM_START_REBUILD:team-activity"
                        }
                        """)
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.code").isEqualTo("optimistic_lock_conflict")
                .jsonPath("$.currentVersion").isEqualTo(5)
                .jsonPath("$.details.expectedVersion").isEqualTo("3")
                .jsonPath("$.details.actualVersion").isEqualTo("5");
    }

    @Test
    void retryValidateCancelAndFailUseDedicatedTypedEndpoints() {
        ProjectionRebuildJobId oldJob = ProjectionRebuildJobId.generate();
        ProjectionRebuildJobId currentJob = ProjectionRebuildJobId.generate();
        when(projections.retry(any())).thenReturn(result(
                new ProjectionGeneration(3), currentJob,
                ProjectionGenerationStatus.BUILDING,
                ProjectionRebuildStatus.BUILDING,
                OptionalLong.empty()));
        client.post()
                .uri(organizationRoute("/projections/team-activity/rebuilds/"
                        + oldJob.value() + "/retry"))
                .header(ApiHeaders.IDEMPOTENCY_KEY, "retry-job")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "expectedRetryOfJobVersion": 4,
                          "expectedDefinitionVersion": 1,
                          "expectedPointerVersion": 3,
                          "confirmation": "CONFIRM_RETRY_REBUILD:team-activity"
                        }
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.generationVersion").isEqualTo(0)
                .jsonPath("$.rebuildJobVersion").isEqualTo(0);

        ArgumentCaptor<RetryProjectionRebuildCommand> retry =
                ArgumentCaptor.forClass(RetryProjectionRebuildCommand.class);
        verify(projections).retry(retry.capture());
        assertEquals(4, retry.getValue().expectedRetryOfJobVersion());

        when(projections.validate(any())).thenReturn(result(
                new ProjectionGeneration(3), currentJob,
                ProjectionGenerationStatus.VALIDATING,
                ProjectionRebuildStatus.VALIDATING,
                OptionalLong.empty()));
        client.post()
                .uri(organizationRoute(
                        "/projections/team-activity/generations/3/validate"))
                .header(ApiHeaders.IDEMPOTENCY_KEY, "validate-generation")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "expectedDefinitionVersion": 1,
                          "rebuildJobId": "%s",
                          "expectedGenerationVersion": 0,
                          "expectedJobVersion": 0,
                          "confirmation": "CONFIRM_VALIDATE_GENERATION:team-activity:3"
                        }
                        """.formatted(currentJob.value()))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.generationVersion").isEqualTo(1)
                .jsonPath("$.rebuildJobVersion").isEqualTo(1);

        ArgumentCaptor<ValidateProjectionGenerationCommand> validate =
                ArgumentCaptor.forClass(ValidateProjectionGenerationCommand.class);
        verify(projections).validate(validate.capture());
        assertEquals(new ProjectionGeneration(3), validate.getValue().generation());

        when(projections.terminate(any()))
                .thenReturn(result(
                        new ProjectionGeneration(3), currentJob,
                        ProjectionGenerationStatus.CANCELLED,
                        ProjectionRebuildStatus.CANCELLED,
                        OptionalLong.empty()))
                .thenReturn(result(
                        new ProjectionGeneration(3), currentJob,
                        ProjectionGenerationStatus.FAILED,
                        ProjectionRebuildStatus.FAILED,
                        OptionalLong.empty()));
        client.post()
                .uri(organizationRoute("/projections/team-activity/generations/3/rebuilds/"
                        + currentJob.value() + "/cancel"))
                .header(ApiHeaders.IDEMPOTENCY_KEY, "cancel-generation")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "expectedGenerationVersion": 1,
                          "expectedJobVersion": 1,
                          "confirmation": "CONFIRM_CANCEL_REBUILD:team-activity:3"
                        }
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.generationStatus").isEqualTo("CANCELLED")
                .jsonPath("$.generationVersion").isEqualTo(2);

        client.post()
                .uri(organizationRoute("/projections/team-activity/generations/3/rebuilds/"
                        + currentJob.value() + "/fail"))
                .header(ApiHeaders.IDEMPOTENCY_KEY, "fail-generation")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "expectedGenerationVersion": 1,
                          "expectedJobVersion": 1,
                          "failureCode": "VALIDATION_DIVERGED",
                          "confirmation": "CONFIRM_FAIL_REBUILD:team-activity:3"
                        }
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.generationStatus").isEqualTo("FAILED");

        ArgumentCaptor<TerminateProjectionRebuildCommand> terminate =
                ArgumentCaptor.forClass(TerminateProjectionRebuildCommand.class);
        verify(projections, times(2)).terminate(terminate.capture());
        assertTrue(terminate.getAllValues().get(0).failureCode().isEmpty());
        assertEquals(
                "VALIDATION_DIVERGED",
                terminate.getAllValues().get(1).failureCode().orElseThrow().value());
    }

    private static OperationsMemberHealthSummary summary() {
        return new OperationsMemberHealthSummary(
                NOW,
                OperationsHealthLevel.DEGRADED,
                Arrays.stream(OperationsHealthComponent.values())
                        .map(component -> new OperationsComponentSummary(
                                component,
                                component == OperationsHealthComponent.OUTBOX
                                        ? OperationsHealthLevel.DEGRADED
                                        : OperationsHealthLevel.HEALTHY,
                                component == OperationsHealthComponent.OUTBOX ? 2 : 0,
                                0,
                                0,
                                component == OperationsHealthComponent.OUTBOX ? 1 : 0,
                                component == OperationsHealthComponent.OUTBOX ? 30 : 0,
                                component == OperationsHealthComponent.OUTBOX))
                        .toList());
    }

    private static ProjectionAdministrationResult result(
            ProjectionGeneration generation,
            ProjectionRebuildJobId jobId,
            ProjectionGenerationStatus generationStatus,
            ProjectionRebuildStatus rebuildStatus,
            OptionalLong pointerVersion) {
        return new ProjectionAdministrationResult(
                ORGANIZATION_ID,
                PROJECTION,
                generation,
                jobId,
                generationStatus,
                rebuildStatus,
                pointerVersion);
    }

    private static String teamRoute(String suffix) {
        return "/api/v1/organizations/" + ORGANIZATION_ID + "/teams/" + TEAM_ID
                + "/operations" + suffix;
    }

    private static String organizationRoute(String suffix) {
        return "/api/v1/organizations/" + ORGANIZATION_ID + "/operations" + suffix;
    }
}
