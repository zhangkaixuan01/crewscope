package io.crewscope.server.api;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import io.crewscope.application.collaboration.LarkConnectionView;
import io.crewscope.application.inbox.InboxItemView;
import io.crewscope.application.notification.NotificationDeliveryView;
import io.crewscope.application.notification.NotificationTemplateView;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/** Stable M6-Q01 field-name leakage probes for every public team-observation response shape. */
class M6PublicProjectionFixedAttackSetM6Q01Test {

    private static final Set<String> FORBIDDEN_COMPONENT_FRAGMENTS = Set.of(
            "apikey",
            "secret",
            "credentialid",
            "credentialreference",
            "ciphertext",
            "plaintext",
            "authorization",
            "bearer",
            "accesstoken",
            "refreshtoken",
            "tenantkey",
            "openid",
            "unionid",
            "providermessageid",
            "endpoint",
            "baseurl",
            "rawbody",
            "rawpayload",
            "providerbody",
            "prompt",
            "systemprompt",
            "toolarguments",
            "toolresult",
            "reasoning",
            "statesnapshot",
            "workerid",
            "leaseid",
            "leasetoken",
            "claimtoken",
            "fencingtoken",
            "database",
            "sql");

    @TestFactory
    Stream<DynamicTest> blocksSensitiveFieldsAcrossEveryM6PublicProjection() {
        List<Class<?>> projections = projections();
        if (projections.size() != 50) {
            throw new IllegalStateException("M6-Q01 public projection attack denominator must remain 50");
        }
        return IntStream.range(0, projections.size()).mapToObj(index -> {
            Class<?> projection = projections.get(index);
            return dynamicTest(
                    "LK-%02d-%s".formatted(index + 1, projection.getSimpleName()),
                    () -> assertPublicProjection(projection));
        });
    }

    private static List<Class<?>> projections() {
        return List.of(
                ActivityResponse.class,
                ActivityResponse.ActivitySubjectResponse.class,
                ActivityResponse.ActivityActorResponse.class,
                ActivityResponse.ActivityReferenceResponse.class,
                ActivityResponse.ActivityPayloadResponse.class,
                ActivityPageResponse.class,
                ActivitySnapshotResponse.class,
                InboxItemResponse.class,
                InboxItemResponse.SourceResponse.class,
                InboxItemView.class,
                InboxPageResponse.class,
                InboxCountsResponse.class,
                InboxCountsResponse.CountResponse.class,
                InboxTargetResponse.class,
                AuditEventResponse.class,
                AuditEventResponse.IdentityResponse.class,
                AuditEventResponse.SubjectResponse.class,
                AuditEventResponse.ProviderResponse.class,
                AuditEventResponse.CorrelationResponse.class,
                AuditPageResponse.class,
                AuditExportResponse.class,
                CorrelationPageResponse.class,
                CorrelationPageResponse.EventResponse.class,
                CorrelationPageResponse.ReferenceResponse.class,
                CorrelationPageResponse.ObjectResponse.class,
                LarkConnectionView.class,
                NotificationDeliveryView.class,
                NotificationTemplateView.class,
                NotificationTemplateView.VariableView.class,
                LarkAdministrationController.PreflightResponse.class,
                LarkAdministrationController.HealthResponse.class,
                LarkAdministrationController.MappingResponse.class,
                LarkAdministrationController.MappingPageResponse.class,
                LarkAdministrationController.PreferenceResponse.class,
                LarkAdministrationController.DeliveryPageResponse.class,
                OperationsController.ComponentHealthResponse.class,
                OperationsController.HealthSummaryResponse.class,
                OperationsController.ProjectionDiagnosticResponse.class,
                OperationsController.OutboxRecoveryCandidateResponse.class,
                OperationsController.ProjectionRecoveryCandidateResponse.class,
                OperationsController.NotificationRecoveryCandidateResponse.class,
                OperationsController.AdministratorDiagnosticsResponse.class,
                OperationsController.RecoveryResponse.class,
                OperationsController.ProjectionCommandResponse.class,
                TeamObserverController.SessionResponse.class,
                TeamObserverController.TeamObserverEventResponse.class,
                TeamObserverController.TeamSummaryResponse.class,
                TeamObserverController.SummaryEntryResponse.class,
                TeamObserverController.EvidenceResponse.class,
                TeamObserverController.CancelResponse.class);
    }

    private static void assertPublicProjection(Class<?> projection) {
        assertTrue(projection.isRecord(), () -> projection.getName() + " must remain a record");
        List<String> leaked = Arrays.stream(projection.getRecordComponents())
                .map(RecordComponent::getName)
                .map(M6PublicProjectionFixedAttackSetM6Q01Test::normalize)
                .filter(M6PublicProjectionFixedAttackSetM6Q01Test::isForbidden)
                .toList();
        assertTrue(
                leaked.isEmpty(),
                () -> projection.getName() + " exposes forbidden public components " + leaked);
    }

    private static String normalize(String value) {
        return value.replace("_", "").replace("-", "").toLowerCase();
    }

    private static boolean isForbidden(String value) {
        return FORBIDDEN_COMPONENT_FRAGMENTS.stream().anyMatch(value::contains);
    }
}
