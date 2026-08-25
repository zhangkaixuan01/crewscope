package io.crewscope.application.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.action.ActionKind;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * M6-S03 test-only protocol spike for Inbox disposition and preauthorized notifications.
 *
 * <p>The nested model deliberately stays outside production code. It freezes the domain invariants
 * that M6-D02/D03 and M6-E03/E04 must implement without creating their aggregates or V27 schema
 * early. M5 GitHub actions continue to use their existing Confirmation path.
 */
class InboxNotificationM6S03Test {

    private static final UUID ORGANIZATION_ID =
            UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID MEMBER_ID =
            UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID SOURCE_ID =
            UUID.fromString("30000000-0000-0000-0000-000000000003");
    private static final SourceKey SOURCE = new SourceKey(
            ORGANIZATION_ID, MEMBER_ID, "REVIEW_REQUIRED", "REVIEW", SOURCE_ID, 7);

    private InboxProjection inbox;
    private TemplateRegistry templates;
    private NotificationPlanner planner;

    @BeforeEach
    void setUp() {
        inbox = new InboxProjection();
        templates = new TemplateRegistry();
        templates.register(new FixedTemplate(
                "review-required", 3, Set.of("workItemTitle", "reviewUrl")));
        templates.register(new FixedTemplate(
                "review-required", 4, Set.of("workItemTitle", "reviewUrl")));
        planner = new NotificationPlanner(templates);
    }

    @Test
    void rebuildingSourceGenerationPreservesReadAndArchivedDisposition() {
        inbox.project(11, openSource(SOURCE));
        InboxItem original = inbox.item(11, SOURCE);
        inbox.dispose(original.id(), Disposition.READ);
        inbox.dispose(original.id(), Disposition.ARCHIVED);

        inbox.project(12, openSource(SOURCE));
        InboxItem rebuilt = inbox.item(12, SOURCE);

        assertEquals(original.id(), rebuilt.id());
        assertEquals(Disposition.ARCHIVED, inbox.disposition(rebuilt.id()));
        assertEquals(1, inbox.dispositionCount());
    }

    @Test
    void replayingOneSourceCreatesOneInboxItemAndTerminalFactsCloseWithoutDeletingHistory() {
        InboxSource source = openSource(SOURCE);
        inbox.project(11, source);
        inbox.project(11, source);

        assertEquals(1, inbox.itemCount(11));
        assertEquals(1, inbox.openItemCount(11));

        inbox.project(11, source.close("REVIEW_SUPERSEDED"));

        assertEquals(1, inbox.itemCount(11));
        assertEquals(0, inbox.openItemCount(11));
        assertEquals("REVIEW_SUPERSEDED", inbox.item(11, SOURCE).closeReason());
    }

    @Test
    void sameSourceAndAuthorizationFactsCreateOneLogicalNotificationAndReceipt() {
        NotificationFacts facts = baselineFacts();
        NotificationAction first = planner.planAutomatic(facts);
        NotificationAction replay = planner.planAutomatic(facts);
        DeliveryHarness delivery = new DeliveryHarness();

        NotificationReceipt firstReceipt = delivery.succeed(first, "lark-message-42");
        NotificationReceipt replayReceipt = delivery.succeed(replay, "lark-message-42");

        assertSame(first, replay);
        assertSame(firstReceipt, replayReceipt);
        assertEquals(1, planner.actionCount());
        assertEquals(1, delivery.providerWriteCount());
        assertEquals(1, delivery.receiptCount());
    }

    @Test
    void everyAuthorizationFactDriftCreatesNewDigestAndInvalidatesPriorDispatch() {
        NotificationFacts baseline = baselineFacts();
        NotificationAction original = planner.planAutomatic(baseline);
        List<NotificationFacts> changedFacts = List.of(
                baseline.withTemplateVersion(4),
                baseline.withVariable("workItemTitle", "Release 2.0"),
                baseline.withRecipientMappingVersion(6),
                baseline.withProviderBindingVersion(10),
                baseline.withConnectionGrantVersion(12),
                baseline.withPolicyVersion(14),
                baseline.withPreferenceVersion(16));

        Set<String> changedDigests = changedFacts.stream()
                .map(planner::planAutomatic)
                .map(NotificationAction::digest)
                .collect(java.util.stream.Collectors.toSet());

        assertEquals(changedFacts.size(), changedDigests.size());
        assertTrue(changedDigests.stream().noneMatch(original.digest()::equals));
        assertEquals(ActionStatus.INVALIDATED, original.status());
        assertEquals(1 + changedFacts.size(), planner.actionCount());
    }

    @Test
    void policyPreauthorizationOnlyAcceptsRegisteredFixedTemplateAndExactVariables() {
        NotificationFacts baseline = baselineFacts();

        assertEquals(AuthorizationMode.POLICY_PREAUTHORIZED,
                planner.planAutomatic(baseline).authorization().mode());
        assertThrows(ProtocolViolation.class,
                () -> planner.planAutomatic(baseline.withTemplate("free-form", 1)));
        assertThrows(ProtocolViolation.class,
                () -> planner.planAutomatic(baseline.withVariable("arbitraryBody", "send secret")));
        assertThrows(ProtocolViolation.class,
                () -> planner.plan(ActionOperation.PUSH_BRANCH, baseline));
        assertThrows(ProtocolViolation.class,
                () -> planner.plan(ActionOperation.CREATE_DRAFT_PR, baseline));
    }

    @Test
    void githubActionsRetainM5HumanConfirmationBoundary() {
        assertTrue(M5AuthorizationBoundary.requiresExactHumanConfirmation(ActionKind.PUSH_BRANCH));
        assertTrue(M5AuthorizationBoundary.requiresExactHumanConfirmation(
                ActionKind.CREATE_DRAFT_PR));
        assertThrows(ProtocolViolation.class,
                () -> M5AuthorizationBoundary.authorizeWithNotificationPolicy(
                        ActionKind.PUSH_BRANCH));
        assertThrows(ProtocolViolation.class,
                () -> M5AuthorizationBoundary.authorizeWithNotificationPolicy(
                        ActionKind.CREATE_DRAFT_PR));
    }

    @Test
    void finalFailureRedeliveryCreatesNewCommandAndKeepsHistoricalReceiptImmutable() {
        NotificationAction original = planner.planAutomatic(baselineFacts());
        DeliveryHarness delivery = new DeliveryHarness();
        NotificationReceipt failed = delivery.failFinal(original, "RECIPIENT_UNAVAILABLE");
        RedeliveryCommand command = new RedeliveryCommand(
                UUID.fromString("40000000-0000-0000-0000-000000000004"), original.id());

        NotificationAction redelivery = planner.planRedelivery(
                command, original, failed, baselineFacts());
        NotificationAction commandReplay = planner.planRedelivery(
                command, original, failed, baselineFacts());
        NotificationReceipt succeeded = delivery.succeed(redelivery, "lark-message-43");

        assertSame(redelivery, commandReplay);
        assertNotEquals(original.id(), redelivery.id());
        assertNotEquals(original.digest(), redelivery.digest());
        assertEquals(original.id(), redelivery.redeliveryOf());
        assertEquals(ReceiptResult.FAILED_FINAL, failed.result());
        assertEquals("RECIPIENT_UNAVAILABLE", failed.externalReference());
        assertEquals(ReceiptResult.SUCCEEDED, succeeded.result());
        assertEquals(2, delivery.receiptCount());
    }

    private static InboxSource openSource(SourceKey sourceKey) {
        return new InboxSource(sourceKey, true, "");
    }

    private static NotificationFacts baselineFacts() {
        return new NotificationFacts(
                SOURCE,
                "review-required",
                3,
                Map.of(
                        "workItemTitle", "Release 1.0",
                        "reviewUrl", "https://crewscope.invalid/reviews/42"),
                "lark-member-mapping-5",
                5,
                "lark-provider-binding-9",
                9,
                "lark-connection-grant-11",
                11,
                "team-notification-policy-13",
                13,
                15,
                AuthorizationMode.POLICY_PREAUTHORIZED);
    }

    private enum Disposition {
        UNREAD,
        READ,
        ACTED,
        ARCHIVED
    }

    private enum AuthorizationMode {
        POLICY_PREAUTHORIZED
    }

    private enum ActionOperation {
        NOTIFY_COLLABORATION,
        PUSH_BRANCH,
        CREATE_DRAFT_PR
    }

    private enum ActionStatus {
        PLANNED,
        INVALIDATED
    }

    private enum ReceiptResult {
        SUCCEEDED,
        FAILED_FINAL
    }

    private record SourceKey(
            UUID organizationId,
            UUID memberId,
            String itemType,
            String sourceType,
            UUID sourceId,
            long sourceRevision) {

        private SourceKey {
            Objects.requireNonNull(organizationId, "organizationId");
            Objects.requireNonNull(memberId, "memberId");
            Objects.requireNonNull(itemType, "itemType");
            Objects.requireNonNull(sourceType, "sourceType");
            Objects.requireNonNull(sourceId, "sourceId");
            if (sourceRevision < 0) {
                throw new ProtocolViolation("sourceRevision must not be negative");
            }
        }

        String canonical() {
            return CanonicalDigest.encode(
                    organizationId.toString(), memberId.toString(), itemType, sourceType,
                    sourceId.toString(), Long.toString(sourceRevision));
        }
    }

    private record InboxSource(SourceKey key, boolean open, String closeReason) {

        private InboxSource {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(closeReason, "closeReason");
            if (open == !closeReason.isEmpty()) {
                throw new ProtocolViolation("open source and close reason shape is inconsistent");
            }
        }

        InboxSource close(String reason) {
            return new InboxSource(key, false, reason);
        }
    }

    private record InboxItem(String id, InboxSource source) {

        boolean open() {
            return source.open();
        }

        String closeReason() {
            return source.closeReason();
        }
    }

    /** Simulates replaceable generation rows merged with a stable disposition authority. */
    private static final class InboxProjection {

        private final Map<Long, Map<SourceKey, InboxItem>> generationItems = new HashMap<>();
        private final Map<String, Disposition> dispositions = new HashMap<>();

        void project(long generation, InboxSource source) {
            if (generation < 1) {
                throw new ProtocolViolation("generation must be positive");
            }
            String itemId = CanonicalDigest.sha256("inbox-item-v1|" + source.key().canonical());
            generationItems.computeIfAbsent(generation, ignored -> new LinkedHashMap<>())
                    .put(source.key(), new InboxItem(itemId, source));
        }

        void dispose(String itemId, Disposition disposition) {
            if (disposition == Disposition.UNREAD) {
                throw new ProtocolViolation("UNREAD is derived when no disposition exists");
            }
            dispositions.put(itemId, disposition);
        }

        InboxItem item(long generation, SourceKey key) {
            InboxItem item = generationItems.getOrDefault(generation, Map.of()).get(key);
            if (item == null) {
                throw new ProtocolViolation("Inbox item does not exist");
            }
            return item;
        }

        Disposition disposition(String itemId) {
            return dispositions.getOrDefault(itemId, Disposition.UNREAD);
        }

        int itemCount(long generation) {
            return generationItems.getOrDefault(generation, Map.of()).size();
        }

        long openItemCount(long generation) {
            return generationItems.getOrDefault(generation, Map.of()).values().stream()
                    .filter(InboxItem::open)
                    .count();
        }

        int dispositionCount() {
            return dispositions.size();
        }
    }

    private record FixedTemplate(String id, long version, Set<String> variableNames) {

        private FixedTemplate {
            Objects.requireNonNull(id, "id");
            variableNames = Set.copyOf(variableNames);
            if (id.isBlank() || version < 1 || variableNames.isEmpty()) {
                throw new ProtocolViolation("fixed template identity and schema are required");
            }
        }

        String key() {
            return id + "@" + version;
        }
    }

    private static final class TemplateRegistry {

        private final Map<String, FixedTemplate> fixedTemplates = new HashMap<>();

        void register(FixedTemplate template) {
            if (fixedTemplates.putIfAbsent(template.key(), template) != null) {
                throw new ProtocolViolation("fixed template version already exists");
            }
        }

        FixedTemplate require(String id, long version, Map<String, String> variables) {
            FixedTemplate template = fixedTemplates.get(id + "@" + version);
            if (template == null) {
                throw new ProtocolViolation("notification template version is not registered");
            }
            if (!template.variableNames().equals(variables.keySet())) {
                throw new ProtocolViolation("notification variables must exactly match template schema");
            }
            variables.forEach((name, value) -> {
                if (name.isBlank() || value == null || value.isBlank()) {
                    throw new ProtocolViolation("notification variables must be named and non-blank");
                }
            });
            return template;
        }
    }

    private record NotificationFacts(
            SourceKey source,
            String templateId,
            long templateVersion,
            Map<String, String> variables,
            String recipientMappingId,
            long recipientMappingVersion,
            String providerBindingId,
            long providerBindingVersion,
            String connectionGrantId,
            long connectionGrantVersion,
            String policyId,
            long policyVersion,
            long preferenceVersion,
            AuthorizationMode authorizationMode) {

        private NotificationFacts {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(templateId, "templateId");
            variables = Map.copyOf(variables);
            Objects.requireNonNull(recipientMappingId, "recipientMappingId");
            Objects.requireNonNull(providerBindingId, "providerBindingId");
            Objects.requireNonNull(connectionGrantId, "connectionGrantId");
            Objects.requireNonNull(policyId, "policyId");
            Objects.requireNonNull(authorizationMode, "authorizationMode");
            if (templateVersion < 1 || recipientMappingVersion < 1
                    || providerBindingVersion < 1 || connectionGrantVersion < 1
                    || policyVersion < 1 || preferenceVersion < 1) {
                throw new ProtocolViolation("notification authorization versions must be positive");
            }
        }

        NotificationFacts withTemplate(String id, long version) {
            return copy(id, version, variables, recipientMappingVersion, providerBindingVersion,
                    connectionGrantVersion, policyVersion, preferenceVersion);
        }

        NotificationFacts withTemplateVersion(long version) {
            return withTemplate(templateId, version);
        }

        NotificationFacts withVariable(String name, String value) {
            Map<String, String> changed = new HashMap<>(variables);
            changed.put(name, value);
            return copy(templateId, templateVersion, changed, recipientMappingVersion,
                    providerBindingVersion, connectionGrantVersion, policyVersion,
                    preferenceVersion);
        }

        NotificationFacts withRecipientMappingVersion(long version) {
            return copy(templateId, templateVersion, variables, version, providerBindingVersion,
                    connectionGrantVersion, policyVersion, preferenceVersion);
        }

        NotificationFacts withProviderBindingVersion(long version) {
            return copy(templateId, templateVersion, variables, recipientMappingVersion, version,
                    connectionGrantVersion, policyVersion, preferenceVersion);
        }

        NotificationFacts withConnectionGrantVersion(long version) {
            return copy(templateId, templateVersion, variables, recipientMappingVersion,
                    providerBindingVersion, version, policyVersion, preferenceVersion);
        }

        NotificationFacts withPolicyVersion(long version) {
            return copy(templateId, templateVersion, variables, recipientMappingVersion,
                    providerBindingVersion, connectionGrantVersion, version, preferenceVersion);
        }

        NotificationFacts withPreferenceVersion(long version) {
            return copy(templateId, templateVersion, variables, recipientMappingVersion,
                    providerBindingVersion, connectionGrantVersion, policyVersion, version);
        }

        private NotificationFacts copy(
                String changedTemplateId,
                long changedTemplateVersion,
                Map<String, String> changedVariables,
                long changedRecipientVersion,
                long changedBindingVersion,
                long changedGrantVersion,
                long changedPolicyVersion,
                long changedPreferenceVersion) {
            return new NotificationFacts(
                    source, changedTemplateId, changedTemplateVersion, changedVariables,
                    recipientMappingId, changedRecipientVersion, providerBindingId,
                    changedBindingVersion, connectionGrantId, changedGrantVersion, policyId,
                    changedPolicyVersion, changedPreferenceVersion, authorizationMode);
        }
    }

    private record NotificationAuthorization(
            AuthorizationMode mode,
            String templateId,
            long templateVersion,
            String variableHash,
            String recipientMappingId,
            long recipientMappingVersion,
            String providerBindingId,
            long providerBindingVersion,
            String connectionGrantId,
            long connectionGrantVersion,
            String policyId,
            long policyVersion,
            long preferenceVersion) {

        static NotificationAuthorization from(NotificationFacts facts) {
            return new NotificationAuthorization(
                    facts.authorizationMode(), facts.templateId(), facts.templateVersion(),
                    CanonicalDigest.map(facts.variables()), facts.recipientMappingId(),
                    facts.recipientMappingVersion(), facts.providerBindingId(),
                    facts.providerBindingVersion(), facts.connectionGrantId(),
                    facts.connectionGrantVersion(), facts.policyId(), facts.policyVersion(),
                    facts.preferenceVersion());
        }

        String canonical() {
            return CanonicalDigest.encode(
                    mode.name(), templateId, Long.toString(templateVersion), variableHash,
                    recipientMappingId, Long.toString(recipientMappingVersion), providerBindingId,
                    Long.toString(providerBindingVersion), connectionGrantId,
                    Long.toString(connectionGrantVersion), policyId, Long.toString(policyVersion),
                    Long.toString(preferenceVersion));
        }
    }

    private static final class NotificationAction {

        private final String id;
        private final ActionOperation operation;
        private final SourceKey source;
        private final NotificationAuthorization authorization;
        private final String deduplicationKey;
        private final String digest;
        private final String redeliveryOf;
        private ActionStatus status = ActionStatus.PLANNED;

        NotificationAction(
                String id,
                ActionOperation operation,
                SourceKey source,
                NotificationAuthorization authorization,
                String deduplicationKey,
                String redeliveryOf) {
            this.id = id;
            this.operation = operation;
            this.source = source;
            this.authorization = authorization;
            this.deduplicationKey = deduplicationKey;
            this.redeliveryOf = redeliveryOf;
            this.digest = CanonicalDigest.sha256(CanonicalDigest.encode(
                    "notification-planned-action-v1", id, operation.name(), source.canonical(),
                    authorization.canonical(), deduplicationKey, redeliveryOf));
        }

        void invalidate() {
            status = ActionStatus.INVALIDATED;
        }

        String id() {
            return id;
        }

        String digest() {
            return digest;
        }

        String sourceIdentity() {
            return source.canonical();
        }

        NotificationAuthorization authorization() {
            return authorization;
        }

        ActionStatus status() {
            return status;
        }

        String redeliveryOf() {
            return redeliveryOf;
        }
    }

    private record RedeliveryCommand(UUID commandId, String failedActionId) {

        private RedeliveryCommand {
            Objects.requireNonNull(commandId, "commandId");
            Objects.requireNonNull(failedActionId, "failedActionId");
        }
    }

    /** Plans only fixed-template collaboration notifications under a closed authorization snapshot. */
    private static final class NotificationPlanner {

        private final TemplateRegistry templates;
        private final Map<String, NotificationAction> actionsByDigest = new LinkedHashMap<>();
        private final Map<String, NotificationAction> currentAutomaticBySource = new HashMap<>();
        private final Map<UUID, NotificationAction> redeliveriesByCommand = new HashMap<>();

        NotificationPlanner(TemplateRegistry templates) {
            this.templates = templates;
        }

        NotificationAction planAutomatic(NotificationFacts facts) {
            return plan(ActionOperation.NOTIFY_COLLABORATION, facts);
        }

        NotificationAction plan(ActionOperation operation, NotificationFacts facts) {
            if (operation != ActionOperation.NOTIFY_COLLABORATION
                    || facts.authorizationMode() != AuthorizationMode.POLICY_PREAUTHORIZED) {
                throw new ProtocolViolation(
                        "POLICY_PREAUTHORIZED only authorizes NOTIFY_COLLABORATION");
            }
            templates.require(facts.templateId(), facts.templateVersion(), facts.variables());
            NotificationAuthorization authorization = NotificationAuthorization.from(facts);
            String deduplicationKey = CanonicalDigest.sha256(
                    "automatic-notification-v1|" + facts.source().canonical());
            NotificationAction candidate = create(facts, authorization, deduplicationKey, "");
            NotificationAction existing = actionsByDigest.get(candidate.digest());
            if (existing != null) {
                return existing;
            }
            NotificationAction prior = currentAutomaticBySource.put(
                    candidate.sourceIdentity(), candidate);
            if (prior != null) {
                prior.invalidate();
            }
            actionsByDigest.put(candidate.digest(), candidate);
            return candidate;
        }

        NotificationAction planRedelivery(
                RedeliveryCommand command,
                NotificationAction failedAction,
                NotificationReceipt failedReceipt,
                NotificationFacts currentFacts) {
            if (!command.failedActionId().equals(failedAction.id())
                    || !failedReceipt.actionId().equals(failedAction.id())
                    || !failedReceipt.actionDigest().equals(failedAction.digest())
                    || failedReceipt.result() != ReceiptResult.FAILED_FINAL) {
                throw new ProtocolViolation(
                        "redelivery command must reference an exact FAILED_FINAL action receipt");
            }
            NotificationAction replay = redeliveriesByCommand.get(command.commandId());
            if (replay != null) {
                return replay;
            }
            templates.require(
                    currentFacts.templateId(), currentFacts.templateVersion(),
                    currentFacts.variables());
            NotificationAuthorization authorization = NotificationAuthorization.from(currentFacts);
            String deduplicationKey = CanonicalDigest.sha256(
                    "notification-redelivery-v1|" + command.commandId());
            NotificationAction redelivery = create(
                    currentFacts, authorization, deduplicationKey, failedAction.id());
            actionsByDigest.put(redelivery.digest(), redelivery);
            redeliveriesByCommand.put(command.commandId(), redelivery);
            return redelivery;
        }

        private static NotificationAction create(
                NotificationFacts facts,
                NotificationAuthorization authorization,
                String deduplicationKey,
                String redeliveryOf) {
            String id = CanonicalDigest.sha256(CanonicalDigest.encode(
                    "notification-action-id-v1", facts.source().canonical(),
                    authorization.canonical(), deduplicationKey, redeliveryOf));
            return new NotificationAction(
                    id, ActionOperation.NOTIFY_COLLABORATION, facts.source(), authorization,
                    deduplicationKey, redeliveryOf);
        }

        int actionCount() {
            return actionsByDigest.size();
        }
    }

    private record NotificationReceipt(
            String actionId,
            String actionDigest,
            ReceiptResult result,
            String externalReference) {}

    /** Models a unique immutable receipt per logical PlannedAction. */
    private static final class DeliveryHarness {

        private final Map<String, NotificationReceipt> receipts = new HashMap<>();
        private int providerWriteCount;

        NotificationReceipt succeed(NotificationAction action, String externalReference) {
            NotificationReceipt existing = receipts.get(action.id());
            if (existing != null) {
                return existing;
            }
            providerWriteCount++;
            NotificationReceipt receipt = new NotificationReceipt(
                    action.id(), action.digest(), ReceiptResult.SUCCEEDED, externalReference);
            receipts.put(action.id(), receipt);
            return receipt;
        }

        NotificationReceipt failFinal(NotificationAction action, String reasonCode) {
            NotificationReceipt receipt = new NotificationReceipt(
                    action.id(), action.digest(), ReceiptResult.FAILED_FINAL, reasonCode);
            NotificationReceipt existing = receipts.putIfAbsent(action.id(), receipt);
            return existing == null ? receipt : existing;
        }

        int providerWriteCount() {
            return providerWriteCount;
        }

        int receiptCount() {
            return receipts.size();
        }
    }

    /** Documents that M6 notification policy cannot be supplied to the M5 GitHub action gate. */
    private static final class M5AuthorizationBoundary {

        static boolean requiresExactHumanConfirmation(ActionKind kind) {
            return kind == ActionKind.PUSH_BRANCH || kind == ActionKind.CREATE_DRAFT_PR;
        }

        static void authorizeWithNotificationPolicy(ActionKind kind) {
            if (requiresExactHumanConfirmation(kind)) {
                throw new ProtocolViolation("GitHub action requires exact M5 human Confirmation");
            }
        }
    }

    /** Length-prefixed canonical encoding keeps delimiters and map iteration order unambiguous. */
    private static final class CanonicalDigest {

        static String encode(String... values) {
            StringBuilder canonical = new StringBuilder();
            for (String value : values) {
                String required = Objects.requireNonNull(value, "canonical value");
                canonical.append('|').append(required.length()).append(':').append(required);
            }
            return canonical.toString();
        }

        static String map(Map<String, String> values) {
            List<String> entries = new ArrayList<>();
            new TreeMap<>(values).forEach((name, value) -> {
                entries.add(name);
                entries.add(value);
            });
            return sha256(encode(entries.toArray(String[]::new)));
        }

        static String sha256(String value) {
            try {
                byte[] digest = MessageDigest.getInstance("SHA-256")
                        .digest(value.getBytes(StandardCharsets.UTF_8));
                return HexFormat.of().formatHex(digest);
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException("SHA-256 must be available", exception);
            }
        }
    }

    private static final class ProtocolViolation extends RuntimeException {

        ProtocolViolation(String message) {
            super(message);
        }
    }
}
