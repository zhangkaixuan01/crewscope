package io.crewscope.domain.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.action.ActionKind;
import io.crewscope.domain.inbox.InboxItem;
import io.crewscope.domain.inbox.InboxItemType;
import io.crewscope.domain.inbox.InboxPriority;
import io.crewscope.domain.inbox.InboxSource;
import io.crewscope.domain.inbox.InboxSourceKey;
import io.crewscope.domain.inbox.InboxSourceRevision;
import io.crewscope.domain.inbox.InboxSourceType;
import io.crewscope.domain.projection.ProjectionGeneration;
import io.crewscope.domain.projection.ProjectionName;
import io.crewscope.domain.provider.ConnectionGrantId;
import io.crewscope.domain.provider.ConnectionId;
import io.crewscope.domain.provider.ProviderBindingId;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.event.SchemaVersion;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamMemberId;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NotificationDomainM6D03Test {

    private static final OrganizationId ORGANIZATION_ID =
            OrganizationId.from("00000000-0000-0000-0000-000000000601");
    private static final TeamId TEAM_ID =
            TeamId.from("00000000-0000-0000-0000-000000000602");
    private static final TeamMemberId MEMBER_ID =
            TeamMemberId.from("00000000-0000-0000-0000-000000000603");
    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-25T10:00:00Z");

    @Test
    void fixedTemplateRejectsArbitraryTextUnknownVariablesAndUntrustedLinks() {
        NotificationTemplate template = template(1);

        NotificationVariables accepted = template.validateVariables(Map.of(
                "workItemTitle", "Review release branch",
                "reviewUrl", "https://crewscope.example/reviews/42"));

        assertEquals(2, accepted.values().size());
        assertThrows(
                DomainValidationException.class,
                () -> template.validateVariables(Map.of(
                        "workItemTitle", "Review release branch",
                        "reviewUrl", "https://crewscope.example/reviews/42",
                        "body", "arbitrary body")));
        assertThrows(
                DomainValidationException.class,
                () -> template.validateVariables(Map.of(
                        "workItemTitle", "Review release branch",
                        "reviewUrl", "https://evil.example/reviews/42")));
        assertThrows(
                DomainValidationException.class,
                () -> template.retire().validateVariables(Map.of(
                        "workItemTitle", "Review release branch",
                        "reviewUrl", "https://crewscope.example/reviews/42")));
    }

    @Test
    void everyAuthorizationCoordinateContributesToDigestAndDriftReason() {
        NotificationIntent intent = intent(template(1));
        NotificationAuthorizationFacts baseline = facts(intent, 1, 1, 1, 1, 1, 1);
        NotificationAuthorizationSnapshot original =
                NotificationAuthorizationSnapshot.captureAutomatic(baseline);
        var changed = java.util.List.of(
                facts(intent(template(2)), 1, 1, 1, 1, 1, 1),
                facts(intentWithTitle(template(1), "Different title"), 1, 1, 1, 1, 1, 1),
                facts(intent, 2, 1, 1, 1, 1, 1),
                facts(intent, 1, 2, 1, 1, 1, 1),
                facts(intent, 1, 1, 2, 1, 1, 1),
                facts(intent, 1, 1, 1, 2, 1, 1),
                facts(intent, 1, 1, 1, 1, 2, 1),
                facts(intent, 1, 1, 1, 1, 1, 2));

        assertEquals(
                changed.size(),
                changed.stream()
                        .map(NotificationAuthorizationSnapshot::captureAutomatic)
                        .map(NotificationAuthorizationSnapshot::digest)
                        .distinct()
                        .count());
        assertEquals(
                Set.of(
                        NotificationInvalidationReason.TEMPLATE,
                        NotificationInvalidationReason.VARIABLES,
                        NotificationInvalidationReason.RECIPIENT_MAPPING,
                        NotificationInvalidationReason.PROVIDER_BINDING,
                        NotificationInvalidationReason.CONNECTION,
                        NotificationInvalidationReason.GRANT,
                        NotificationInvalidationReason.TEAM_POLICY,
                        NotificationInvalidationReason.MEMBER_PREFERENCE),
                changed.stream()
                        .map(original::invalidationReason)
                        .map(Optional::orElseThrow)
                        .collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void timeoutReconcilesAndFinalReceiptKeepsHistoryImmutable() {
        NotificationAuthorizationFacts facts = facts(intent(template(1)), 1, 1, 1, 1, 1, 1);
        NotificationAuthorizationSnapshot snapshot =
                NotificationAuthorizationSnapshot.captureAutomatic(facts);
        NotificationPlannedAction action = NotificationPlannedAction.plan(
                facts,
                snapshot,
                NOW,
                UtcTimestamp.parse("2026-08-25T11:00:00Z"),
                Optional.empty());
        NotificationDelivery ready = NotificationDelivery.ready(action, NOW);

        NotificationDelivery running = ready.start(0, action, NOW);
        assertThrows(
                IllegalStateException.class,
                () -> running.start(running.version(), action, NOW));
        NotificationDelivery unknown = running.markUnknown(
                running.version(), UtcTimestamp.parse("2026-08-25T10:01:00Z"));
        NotificationDelivery reconciling = unknown.beginReconciliation(
                unknown.version(), UtcTimestamp.parse("2026-08-25T10:02:00Z"));
        NotificationReceipt failure = NotificationReceipt.failed(
                NotificationReceiptId.generate(),
                reconciling,
                action,
                NotificationFailureCode.RECONCILIATION_EXHAUSTED,
                "RECONCILIATION_EXHAUSTED",
                UtcTimestamp.parse("2026-08-25T10:03:00Z"));
        NotificationDelivery terminal = reconciling.failFinal(reconciling.version(), failure);

        assertEquals(NotificationDeliveryStatus.UNKNOWN, unknown.status());
        assertEquals(NotificationDeliveryStatus.RECONCILING, reconciling.status());
        assertEquals(NotificationDeliveryStatus.FAILED_FINAL, terminal.status());
        assertEquals(failure, terminal.receipt().orElseThrow());
        assertThrows(
                IllegalStateException.class,
                () -> terminal.beginReconciliation(terminal.version(), NOW));
        assertThrows(
                IllegalStateException.class,
                () -> terminal.invalidate(
                        terminal.version(),
                        NotificationInvalidationReason.GRANT,
                        NotificationReceipt.invalidated(
                                NotificationReceiptId.generate(),
                                terminal,
                                action,
                                NotificationInvalidationReason.GRANT,
                                NOW)));
    }

    @Test
    void notificationUsesLowRiskPolicyActionOutsideM5ConfirmationBundle() {
        NotificationAuthorizationFacts facts = facts(intent(template(1)), 1, 1, 1, 1, 1, 1);
        NotificationAuthorizationSnapshot snapshot =
                NotificationAuthorizationSnapshot.captureAutomatic(facts);
        NotificationPlannedAction action = NotificationPlannedAction.plan(
                facts,
                snapshot,
                NOW,
                UtcTimestamp.parse("2026-08-25T11:00:00Z"),
                Optional.empty());

        assertEquals(ActionKind.NOTIFY_COLLABORATION, action.parameters().kind());
        assertEquals(NotificationAuthorizationMode.POLICY_PREAUTHORIZED, action.authority().mode());
        assertTrue(action.redeliveryOf().isEmpty());
    }

    private static NotificationTemplate template(long version) {
        return new NotificationTemplate(
                new NotificationTemplateRef(
                        new NotificationTemplateId(
                                UUID.fromString("00000000-0000-0000-0000-000000000604")),
                        new NotificationTemplateVersion(version)),
                "review-required",
                Map.of(
                        "workItemTitle", NotificationVariableSpec.text("workItemTitle", 200),
                        "reviewUrl", NotificationVariableSpec.trustedLink(
                                "reviewUrl",
                                500,
                                Set.of(TrustedNotificationOrigin.https("crewscope.example")))),
                NotificationTemplateStatus.PUBLISHED);
    }

    private static NotificationIntent intent(NotificationTemplate template) {
        return intentWithTitle(template, "Review release branch");
    }

    private static NotificationIntent intentWithTitle(
            NotificationTemplate template, String title) {
        InboxSourceKey key = new InboxSourceKey(
                ORGANIZATION_ID,
                MEMBER_ID,
                InboxItemType.REVIEW,
                InboxSourceType.REVIEW_REQUEST,
                UUID.fromString("00000000-0000-0000-0000-000000000605"),
                InboxSourceRevision.INITIAL);
        InboxItem item = InboxItem.project(
                TEAM_ID,
                new ProjectionName("member-inbox"),
                ProjectionGeneration.FIRST,
                SchemaVersion.V1,
                InboxSource.open(key, InboxPriority.HIGH, Optional.empty(), NOW));
        return NotificationIntent.fromOpenInbox(
                item,
                template,
                Map.of(
                        "workItemTitle", title,
                        "reviewUrl", "https://crewscope.example/reviews/42"),
                NOW);
    }

    private static NotificationAuthorizationFacts facts(
            NotificationIntent intent,
            long recipientVersion,
            long bindingVersion,
            long connectionVersion,
            long grantVersion,
            long policyVersion,
            long preferenceVersion) {
        return new NotificationAuthorizationFacts(
                intent,
                new NotificationRecipientMappingId(
                        UUID.fromString("00000000-0000-0000-0000-000000000606")),
                recipientVersion,
                new ProviderBindingId(
                        UUID.fromString("00000000-0000-0000-0000-000000000607")),
                bindingVersion,
                new ConnectionId(
                        UUID.fromString("00000000-0000-0000-0000-000000000608")),
                connectionVersion,
                new ConnectionGrantId(
                        UUID.fromString("00000000-0000-0000-0000-000000000609")),
                grantVersion,
                new TeamNotificationPolicyId(
                        UUID.fromString("00000000-0000-0000-0000-000000000610")),
                policyVersion,
                new NotificationPreference(
                        MEMBER_ID,
                        true,
                        Set.of(InboxItemType.REVIEW),
                        Optional.empty(),
                        preferenceVersion));
    }
}
