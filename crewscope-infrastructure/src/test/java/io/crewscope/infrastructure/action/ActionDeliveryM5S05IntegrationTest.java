package io.crewscope.infrastructure.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Freezes the M5-S05 ActionBundle delivery contract without introducing the production M5 domain
 * model ahead of M5-D08 and M5-D09.
 */
class ActionDeliveryM5S05IntegrationTest {

    private static final Instant CONFIRMED_AT = Instant.parse("2026-08-22T08:00:00Z");
    private static final String CONNECTION_ID = "github-connection-1";

    @Test
    void invalidatesConfirmationWhenAnySecurityOrExecutionFactChanges() {
        ActionBundleShape original = fixtureBundle();
        ConfirmationShape confirmation = new ConfirmationShape(
                original.digest(), CONFIRMED_AT, CONFIRMED_AT.plusSeconds(60));

        assertTrue(confirmation.authorizes(original, CONFIRMED_AT.plusSeconds(1)));
        assertFalse(confirmation.authorizes(original.withAction(0,
                original.actions().get(0).withParameter("deliveryHead", "bbbbbbbb")),
                CONFIRMED_AT.plusSeconds(1)));
        assertFalse(confirmation.authorizes(original.withAction(0,
                original.actions().get(0).withReviewId("review-2")), CONFIRMED_AT.plusSeconds(1)));
        assertFalse(confirmation.authorizes(original.withAction(0,
                original.actions().get(0).withReviewVersion(8)), CONFIRMED_AT.plusSeconds(1)));
        assertFalse(confirmation.authorizes(original.withAction(0,
                original.actions().get(0).withResponsibilityId("responsibility-2")),
                CONFIRMED_AT.plusSeconds(1)));
        assertFalse(confirmation.authorizes(original.withAction(0,
                original.actions().get(0).withResponsibilityVersion(12)),
                CONFIRMED_AT.plusSeconds(1)));
        assertFalse(confirmation.authorizes(original.withAction(0,
                original.actions().get(0).withBindingId("binding-2")), CONFIRMED_AT.plusSeconds(1)));
        assertFalse(confirmation.authorizes(original.withAction(0,
                original.actions().get(0).withBindingVersion(5)), CONFIRMED_AT.plusSeconds(1)));
        assertFalse(confirmation.authorizes(original.withAction(0,
                original.actions().get(0).withGrantId("grant-2")), CONFIRMED_AT.plusSeconds(1)));
        assertFalse(confirmation.authorizes(original.withAction(0,
                original.actions().get(0).withGrantVersion(4)), CONFIRMED_AT.plusSeconds(1)));
        assertFalse(confirmation.authorizes(original.withAction(0,
                original.actions().get(0).withPolicyId("policy-2")), CONFIRMED_AT.plusSeconds(1)));
        assertFalse(confirmation.authorizes(original.withAction(0,
                original.actions().get(0).withPolicyVersion(20)), CONFIRMED_AT.plusSeconds(1)));
        assertFalse(confirmation.authorizes(original.withAction(0,
                original.actions().get(0).withSafetyOverlayVersion(3)),
                CONFIRMED_AT.plusSeconds(1)));
        assertFalse(confirmation.authorizes(original.withAction(0,
                original.actions().get(0).withRiskClass("CRITICAL")), CONFIRMED_AT.plusSeconds(1)));
        assertFalse(confirmation.authorizes(original.withAction(0,
                original.actions().get(0).withValidUntil(CONFIRMED_AT.plusSeconds(301))),
                CONFIRMED_AT.plusSeconds(1)));
        assertFalse(confirmation.authorizes(original.withAction(0,
                original.actions().get(0).withTargetPrecondition("remote-head-2")),
                CONFIRMED_AT.plusSeconds(1)));
        assertFalse(confirmation.authorizes(original.reversed(), CONFIRMED_AT.plusSeconds(1)));
        assertFalse(confirmation.authorizes(original.withAction(1,
                original.actions().get(1).withoutDependencies()), CONFIRMED_AT.plusSeconds(1)));
        assertFalse(confirmation.authorizes(original, CONFIRMED_AT.minusMillis(1)));
        assertFalse(confirmation.authorizes(original, CONFIRMED_AT.plusSeconds(60)));

        assertNotEquals(original.actions().get(0).digest(), original.actions().get(1).digest());
        assertEquals(original.digest(), fixtureBundle().digest());
    }

    @Test
    void exposesDispatchOnlyAfterCommitAndNeverCallsProviderForRollback() {
        DeliveryStore store = new DeliveryStore();
        ExternalGitHubProbe provider = new ExternalGitHubProbe();
        TransactionalActionPlanner planner = new TransactionalActionPlanner(store);
        ActionBundleShape bundle = fixtureBundle();

        PlannerTransaction rolledBack = planner.begin(bundle);
        assertEquals(0, store.dispatchCount());
        assertEquals(0, provider.totalWrites());
        rolledBack.rollback();
        assertEquals(0, store.dispatchCount());

        PlannerTransaction committed = planner.begin(bundle);
        assertEquals(0, store.dispatchCount());
        committed.commit();
        assertEquals(2, store.dispatchCount());

        ActionWorkerProbe worker = new ActionWorkerProbe(store, provider, Duration.ofSeconds(30));
        worker.runNext(CONFIRMED_AT);

        assertEquals(1, provider.pushWrites());
        assertEquals(0, provider.pullRequestWrites());
        assertEquals(ActionState.SUCCEEDED, store.action("push").state());
        assertEquals(ActionState.READY, store.action("pr").state());

        worker.runNext(CONFIRMED_AT.plusSeconds(1));

        assertEquals(1, provider.pushWrites());
        assertEquals(1, provider.pullRequestWrites());
        assertEquals(ActionState.SUCCEEDED, store.action("pr").state());
        assertEquals(2, store.receiptCount());
    }

    @Test
    void reconcilesPushAfterWorkerExitAndRejectsStaleFencingReceipt() {
        DeliveryStore store = committedStore();
        ExternalGitHubProbe provider = new ExternalGitHubProbe();
        provider.exitAfterNextPush();
        ActionWorkerProbe firstWorker = new ActionWorkerProbe(store, provider, Duration.ofSeconds(30));

        assertThrows(SimulatedProcessExit.class, () -> firstWorker.runNext(CONFIRMED_AT));
        ClaimedAction staleClaim = firstWorker.lastClaim().orElseThrow();
        assertEquals(1, provider.pushWrites());
        assertEquals(0, store.receiptCount());
        assertEquals(ActionState.RUNNING, store.action("push").state());

        ActionWorkerProbe replacement = new ActionWorkerProbe(store, provider, Duration.ofSeconds(30));
        assertFalse(replacement.runNext(CONFIRMED_AT.plusSeconds(29)));
        assertTrue(replacement.runNext(CONFIRMED_AT.plusSeconds(31)));

        assertEquals(1, provider.pushWrites());
        assertEquals(1, store.receiptCount());
        assertEquals(ActionState.SUCCEEDED, store.action("push").state());
        assertEquals(2, store.observationsFor("push").size());
        assertThrows(StaleFencingToken.class, () -> store.complete(
                staleClaim,
                new ActionReceiptShape("push", "push:feature/crewscope:aaaaaaaa", ResultKind.SUCCESS,
                        "stale-worker-evidence", CONFIRMED_AT.plusSeconds(32))));
        assertEquals(1, store.receiptCount());
    }

    @Test
    void reconcilesUnknownPullRequestAndRetriesOnlyFailedDependentAction() {
        DeliveryStore store = committedStore();
        ExternalGitHubProbe provider = new ExternalGitHubProbe();
        ActionWorkerProbe worker = new ActionWorkerProbe(store, provider, Duration.ofSeconds(30));
        worker.runNext(CONFIRMED_AT);
        provider.loseNextPullRequestResponse();

        worker.runNext(CONFIRMED_AT.plusSeconds(1));

        assertEquals(ActionState.UNKNOWN, store.action("pr").state());
        assertEquals(1, provider.pushWrites());
        assertEquals(1, provider.pullRequestWrites());
        assertEquals(1, store.receiptCount());

        worker.reconcile("pr", CONFIRMED_AT.plusSeconds(2));

        assertEquals(ActionState.SUCCEEDED, store.action("pr").state());
        assertEquals(1, provider.pushWrites());
        assertEquals(1, provider.pullRequestWrites());
        assertEquals(2, store.receiptCount());
        assertEquals(1, store.externalResultCount());

        worker.reconcile("pr", CONFIRMED_AT.plusSeconds(3));
        assertEquals(1, provider.pullRequestWrites());
        assertEquals(2, store.receiptCount());
    }

    @Test
    void mergesDuplicateOutOfOrderWebhooksAndQueriesIntoOneExternalResult() {
        DeliveryStore store = committedStore();
        ExternalGitHubProbe provider = new ExternalGitHubProbe();
        ActionWorkerProbe worker = new ActionWorkerProbe(store, provider, Duration.ofSeconds(30));
        worker.runNext(CONFIRMED_AT);
        worker.runNext(CONFIRMED_AT.plusSeconds(1));
        WebhookReconcileProbe webhook = new WebhookReconcileProbe(store);
        String resultKey = "github:pr:42";

        webhook.accept(new WebhookEventShape(CONNECTION_ID, "pr", "delivery-2", resultKey, "MERGED", 3,
                CONFIRMED_AT.plusSeconds(20)));
        webhook.accept(new WebhookEventShape(CONNECTION_ID, "pr", "delivery-2", resultKey, "MERGED", 3,
                CONFIRMED_AT.plusSeconds(20)));
        webhook.accept(new WebhookEventShape(CONNECTION_ID, "pr", "delivery-1", resultKey, "OPEN", 1,
                CONFIRMED_AT.plusSeconds(10)));
        webhook.accept(new WebhookEventShape(CONNECTION_ID, "pr", "delivery-3", resultKey, "CLOSED", 3,
                CONFIRMED_AT.plusSeconds(30)));
        webhook.mergeQuery(CONNECTION_ID, "pr", resultKey, "CLOSED", 2,
                CONFIRMED_AT.plusSeconds(15));
        webhook.accept(new WebhookEventShape("github-connection-2", "pr", "delivery-2", resultKey,
                "OPEN", 1, CONFIRMED_AT.plusSeconds(5)));

        ExternalResultShape result = store.externalResult(CONNECTION_ID, resultKey).orElseThrow();
        assertEquals("MERGED", result.providerStatus());
        assertEquals(3, result.providerVersion());
        assertEquals(4, webhook.acceptedDeliveryCount());
        assertEquals(2, store.externalResultCount());
        assertEquals(2, store.receiptCount());
        assertTrue(store.observationsFor("pr").size() >= 6);
    }

    @Test
    void requiresEvidenceForManualResolutionAndKeepsManualTerminalStateIrreversible() {
        DeliveryStore store = committedStore();
        ExternalGitHubProbe provider = new ExternalGitHubProbe();
        ActionWorkerProbe worker = new ActionWorkerProbe(store, provider, Duration.ofSeconds(30));
        worker.runNext(CONFIRMED_AT);
        provider.loseNextPullRequestResponseWithoutCreating();
        worker.runNext(CONFIRMED_AT.plusSeconds(1));
        worker.reconcile("pr", CONFIRMED_AT.plusSeconds(2));
        worker.reconcile("pr", CONFIRMED_AT.plusSeconds(3));

        assertEquals(ActionState.MANUAL_REVIEW, store.action("pr").state());
        assertThrows(IllegalArgumentException.class, () -> store.resolveManually(
                "pr", ManualResolution.SUCCEEDED, "member-1", " ", CONFIRMED_AT.plusSeconds(4)));

        store.resolveManually("pr", ManualResolution.FAILED, "member-1",
                "GitHub audit log proves no PR was created", CONFIRMED_AT.plusSeconds(4));
        WebhookReconcileProbe webhook = new WebhookReconcileProbe(store);
        webhook.accept(new WebhookEventShape(CONNECTION_ID, "pr", "late-delivery", "github:pr:42",
                "OPEN", 10,
                CONFIRMED_AT.plusSeconds(50)));

        assertEquals(ActionState.MANUALLY_FAILED, store.action("pr").state());
        assertEquals(1, store.manualAuditCount());
        assertEquals(2, store.receiptCount());
        assertEquals(ResultKind.MANUAL_FAILURE, store.receipt("pr").orElseThrow().result());
        assertThrows(IllegalStateException.class, () -> store.resolveManually(
                "pr", ManualResolution.SUCCEEDED, "member-1", "conflicting evidence",
                CONFIRMED_AT.plusSeconds(51)));
        assertThrows(IllegalStateException.class, () -> store.completeReconcile(
                new ActionReceiptShape("pr", "github:pr:99", ResultKind.SUCCESS,
                        "late conflicting result", CONFIRMED_AT.plusSeconds(52))));
        assertEquals(ResultKind.MANUAL_FAILURE, store.receipt("pr").orElseThrow().result());
    }

    private static ActionBundleShape fixtureBundle() {
        PlannedActionShape push = new PlannedActionShape(
                "push",
                ActionKind.PUSH_BRANCH,
                orderedMap("repositoryId", "repo-101", "branch", "feature/crewscope",
                        "deliveryHead", "aaaaaaaa", "expectedRemoteHead", "ABSENT",
                        "connectionId", CONNECTION_ID),
                List.of(),
                new VersionedFactShape("review-1", 7),
                new VersionedFactShape("responsibility-1", 11),
                new VersionedFactShape("binding-1", 4),
                new VersionedFactShape("grant-1", 3),
                new VersionedFactShape("policy-1", 19),
                2,
                "HIGH",
                CONFIRMED_AT.plusSeconds(300),
                "remote-head-1");
        PlannedActionShape pr = new PlannedActionShape(
                "pr",
                ActionKind.CREATE_DRAFT_PR,
                orderedMap("repositoryId", "repo-101", "head", "feature/crewscope",
                        "base", "main", "headSha", "aaaaaaaa", "title", "CrewScope delivery",
                        "body", "Reviewed delivery", "draft", "true",
                        "connectionId", CONNECTION_ID),
                List.of("push"),
                new VersionedFactShape("review-1", 7),
                new VersionedFactShape("responsibility-1", 11),
                new VersionedFactShape("binding-1", 4),
                new VersionedFactShape("grant-1", 3),
                new VersionedFactShape("policy-1", 19),
                2,
                "HIGH",
                CONFIRMED_AT.plusSeconds(300),
                "base-head-1");
        return new ActionBundleShape("bundle-1", List.of(push, pr));
    }

    private static Map<String, String> orderedMap(String... values) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put(values[index], values[index + 1]);
        }
        return result;
    }

    private static DeliveryStore committedStore() {
        DeliveryStore store = new DeliveryStore();
        new TransactionalActionPlanner(store).begin(fixtureBundle()).commit();
        return store;
    }

    private enum ActionKind {
        PUSH_BRANCH,
        CREATE_DRAFT_PR
    }

    private enum ActionState {
        READY,
        RUNNING,
        UNKNOWN,
        RECONCILING,
        SUCCEEDED,
        FAILED,
        MANUAL_REVIEW,
        MANUALLY_SUCCEEDED,
        MANUALLY_FAILED;

        private boolean terminal() {
            return this == SUCCEEDED || this == FAILED || this == MANUALLY_SUCCEEDED
                    || this == MANUALLY_FAILED;
        }
    }

    private enum ResultKind {
        SUCCESS,
        FAILURE,
        MANUAL_SUCCESS,
        MANUAL_FAILURE
    }

    private enum ManualResolution {
        SUCCEEDED,
        FAILED
    }

    private record VersionedFactShape(String id, long version) {

        private VersionedFactShape withId(String value) {
            return new VersionedFactShape(value, version);
        }

        private VersionedFactShape withVersion(long value) {
            return new VersionedFactShape(id, value);
        }
    }

    private record PlannedActionShape(
            String id,
            ActionKind kind,
            Map<String, String> parameters,
            List<String> dependencies,
            VersionedFactShape reviewDecision,
            VersionedFactShape responsibility,
            VersionedFactShape binding,
            VersionedFactShape grant,
            VersionedFactShape policy,
            long safetyOverlayVersion,
            String riskClass,
            Instant validUntil,
            String targetPrecondition) {

        private PlannedActionShape {
            parameters = Map.copyOf(parameters);
            dependencies = List.copyOf(dependencies);
        }

        private String digest() {
            List<String> facts = new ArrayList<>();
            facts.add("id=" + id);
            facts.add("kind=" + kind);
            parameters.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> facts.add("parameter." + entry.getKey() + "=" + entry.getValue()));
            for (int index = 0; index < dependencies.size(); index++) {
                facts.add("dependency." + index + "=" + dependencies.get(index));
            }
            appendVersionedFact(facts, "reviewDecision", reviewDecision);
            appendVersionedFact(facts, "responsibility", responsibility);
            appendVersionedFact(facts, "binding", binding);
            appendVersionedFact(facts, "grant", grant);
            appendVersionedFact(facts, "policy", policy);
            facts.add("safetyOverlayVersion=" + safetyOverlayVersion);
            facts.add("riskClass=" + riskClass);
            facts.add("validUntil=" + validUntil);
            facts.add("targetPrecondition=" + targetPrecondition);
            return sha256(String.join("\n", facts));
        }

        private static void appendVersionedFact(
                List<String> facts, String name, VersionedFactShape fact) {
            facts.add(name + "Id=" + fact.id());
            facts.add(name + "Version=" + fact.version());
        }

        private PlannedActionShape withParameter(String key, String value) {
            Map<String, String> changed = new HashMap<>(parameters);
            changed.put(key, value);
            return copy(changed, dependencies, reviewDecision, responsibility, binding, grant, policy,
                    safetyOverlayVersion, riskClass, validUntil, targetPrecondition);
        }

        private PlannedActionShape withReviewId(String value) {
            return copy(parameters, dependencies, reviewDecision.withId(value), responsibility,
                    binding, grant, policy, safetyOverlayVersion, riskClass, validUntil,
                    targetPrecondition);
        }

        private PlannedActionShape withReviewVersion(long value) {
            return copy(parameters, dependencies, reviewDecision.withVersion(value), responsibility,
                    binding, grant, policy, safetyOverlayVersion, riskClass, validUntil,
                    targetPrecondition);
        }

        private PlannedActionShape withResponsibilityId(String value) {
            return copy(parameters, dependencies, reviewDecision, responsibility.withId(value),
                    binding, grant, policy, safetyOverlayVersion, riskClass, validUntil,
                    targetPrecondition);
        }

        private PlannedActionShape withResponsibilityVersion(long value) {
            return copy(parameters, dependencies, reviewDecision, responsibility.withVersion(value),
                    binding, grant, policy, safetyOverlayVersion, riskClass, validUntil,
                    targetPrecondition);
        }

        private PlannedActionShape withBindingId(String value) {
            return copy(parameters, dependencies, reviewDecision, responsibility,
                    binding.withId(value), grant, policy, safetyOverlayVersion, riskClass, validUntil,
                    targetPrecondition);
        }

        private PlannedActionShape withBindingVersion(long value) {
            return copy(parameters, dependencies, reviewDecision, responsibility,
                    binding.withVersion(value), grant, policy, safetyOverlayVersion, riskClass,
                    validUntil, targetPrecondition);
        }

        private PlannedActionShape withGrantId(String value) {
            return copy(parameters, dependencies, reviewDecision, responsibility, binding,
                    grant.withId(value), policy, safetyOverlayVersion, riskClass, validUntil,
                    targetPrecondition);
        }

        private PlannedActionShape withGrantVersion(long value) {
            return copy(parameters, dependencies, reviewDecision, responsibility, binding,
                    grant.withVersion(value), policy, safetyOverlayVersion, riskClass, validUntil,
                    targetPrecondition);
        }

        private PlannedActionShape withPolicyId(String value) {
            return copy(parameters, dependencies, reviewDecision, responsibility, binding, grant,
                    policy.withId(value), safetyOverlayVersion, riskClass, validUntil,
                    targetPrecondition);
        }

        private PlannedActionShape withPolicyVersion(long value) {
            return copy(parameters, dependencies, reviewDecision, responsibility, binding, grant,
                    policy.withVersion(value), safetyOverlayVersion, riskClass, validUntil,
                    targetPrecondition);
        }

        private PlannedActionShape withSafetyOverlayVersion(long value) {
            return copy(parameters, dependencies, reviewDecision, responsibility, binding, grant,
                    policy, value, riskClass, validUntil, targetPrecondition);
        }

        private PlannedActionShape withRiskClass(String value) {
            return copy(parameters, dependencies, reviewDecision, responsibility, binding, grant,
                    policy, safetyOverlayVersion, value, validUntil, targetPrecondition);
        }

        private PlannedActionShape withValidUntil(Instant value) {
            return copy(parameters, dependencies, reviewDecision, responsibility, binding, grant,
                    policy, safetyOverlayVersion, riskClass, value, targetPrecondition);
        }

        private PlannedActionShape withTargetPrecondition(String value) {
            return copy(parameters, dependencies, reviewDecision, responsibility, binding, grant,
                    policy, safetyOverlayVersion, riskClass, validUntil, value);
        }

        private PlannedActionShape withoutDependencies() {
            return copy(parameters, List.of(), reviewDecision, responsibility, binding, grant, policy,
                    safetyOverlayVersion, riskClass, validUntil, targetPrecondition);
        }

        private PlannedActionShape copy(
                Map<String, String> newParameters,
                List<String> newDependencies,
                VersionedFactShape newReviewDecision,
                VersionedFactShape newResponsibility,
                VersionedFactShape newBinding,
                VersionedFactShape newGrant,
                VersionedFactShape newPolicy,
                long newSafetyOverlayVersion,
                String newRiskClass,
                Instant newValidUntil,
                String newTargetPrecondition) {
            return new PlannedActionShape(id, kind, newParameters, newDependencies,
                    newReviewDecision, newResponsibility, newBinding, newGrant, newPolicy,
                    newSafetyOverlayVersion, newRiskClass, newValidUntil, newTargetPrecondition);
        }
    }

    private record ActionBundleShape(String id, List<PlannedActionShape> actions) {

        private ActionBundleShape {
            actions = List.copyOf(actions);
            Set<String> actionIds = new HashSet<>();
            for (PlannedActionShape action : actions) {
                if (!actionIds.add(action.id())) {
                    throw new IllegalArgumentException("Action id must be unique in bundle");
                }
                if (!actionIds.containsAll(action.dependencies())) {
                    throw new IllegalArgumentException("Dependencies must reference preceding actions");
                }
            }
        }

        private String digest() {
            List<String> facts = new ArrayList<>();
            facts.add("bundleId=" + id);
            for (int index = 0; index < actions.size(); index++) {
                PlannedActionShape action = actions.get(index);
                facts.add("action." + index + ".id=" + action.id());
                facts.add("action." + index + ".digest=" + action.digest());
            }
            return sha256(String.join("\n", facts));
        }

        private ActionBundleShape withAction(int index, PlannedActionShape action) {
            List<PlannedActionShape> changed = new ArrayList<>(actions);
            changed.set(index, action);
            return new ActionBundleShape(id, changed);
        }

        private ActionBundleShape reversed() {
            List<PlannedActionShape> changed = new ArrayList<>(actions);
            java.util.Collections.reverse(changed);
            return new ActionBundleShape(id, changed.stream()
                    .map(action -> action.kind() == ActionKind.CREATE_DRAFT_PR
                            ? action.withoutDependencies()
                            : action)
                    .toList());
        }
    }

    private record ConfirmationShape(
            String bundleDigest, Instant confirmedAt, Instant expiresAt) {

        private boolean authorizes(ActionBundleShape bundle, Instant now) {
            return !now.isBefore(confirmedAt)
                    && now.isBefore(expiresAt)
                    && bundleDigest.equals(bundle.digest());
        }
    }

    private record ActionDispatchShape(
            String actionId,
            PlannedActionShape action,
            ActionState state,
            long fencingToken,
            String owner,
            Instant leaseUntil) {

        private ActionDispatchShape withClaim(String newOwner, long newFencingToken, Instant newLeaseUntil) {
            return new ActionDispatchShape(actionId, action, ActionState.RUNNING, newFencingToken,
                    newOwner, newLeaseUntil);
        }

        private ActionDispatchShape withState(ActionState newState) {
            return new ActionDispatchShape(actionId, action, newState, fencingToken, owner, leaseUntil);
        }
    }

    private record ClaimedAction(String workerId, String actionId, long fencingToken) {}

    private record ActionReceiptShape(
            String actionId,
            String externalKey,
            ResultKind result,
            String evidence,
            Instant receivedAt) {}

    private record ObservationShape(
            String actionId,
            String source,
            String summary,
            Instant observedAt) {}

    private record ExternalResultShape(
            String actionId,
            String connectionId,
            String externalKey,
            String providerStatus,
            long providerVersion,
            Instant providerUpdatedAt,
            boolean manuallyFinalized) {

        private ExternalResultShape merge(
                String newStatus, long newVersion, Instant newUpdatedAt, boolean manual) {
            if (manuallyFinalized || (!manual && newVersion <= providerVersion)) {
                return this;
            }
            return new ExternalResultShape(actionId, connectionId, externalKey, newStatus,
                    newVersion, newUpdatedAt, manual);
        }
    }

    private record ManualAuditShape(
            String actionId,
            String actorId,
            ManualResolution resolution,
            String evidence,
            Instant resolvedAt) {}

    private static final class TransactionalActionPlanner {

        private final DeliveryStore store;

        private TransactionalActionPlanner(DeliveryStore store) {
            this.store = store;
        }

        private PlannerTransaction begin(ActionBundleShape bundle) {
            return new PlannerTransaction(store, bundle);
        }
    }

    private static final class PlannerTransaction {

        private final DeliveryStore store;
        private final ActionBundleShape bundle;
        private boolean finished;

        private PlannerTransaction(DeliveryStore store, ActionBundleShape bundle) {
            this.store = store;
            this.bundle = bundle;
        }

        private void commit() {
            checkOpen();
            store.publishAfterCommit(bundle);
            finished = true;
        }

        private void rollback() {
            checkOpen();
            finished = true;
        }

        private void checkOpen() {
            if (finished) {
                throw new IllegalStateException("Transaction already completed");
            }
        }
    }

    private static final class DeliveryStore {

        private final Map<String, ActionDispatchShape> dispatches = new LinkedHashMap<>();
        private final Map<String, ActionReceiptShape> receipts = new HashMap<>();
        private final Map<String, ExternalResultShape> externalResults = new HashMap<>();
        private final List<ObservationShape> observations = new ArrayList<>();
        private final List<ManualAuditShape> manualAudits = new ArrayList<>();
        private long fencingSequence;

        private void publishAfterCommit(ActionBundleShape bundle) {
            for (PlannedActionShape action : bundle.actions()) {
                dispatches.put(action.id(), new ActionDispatchShape(
                        action.id(), action, ActionState.READY, 0, null, null));
            }
        }

        private Optional<ClaimedAction> claim(String workerId, Instant now, Duration leaseDuration) {
            for (Map.Entry<String, ActionDispatchShape> entry : dispatches.entrySet()) {
                ActionDispatchShape dispatch = entry.getValue();
                if (!claimable(dispatch, now)) {
                    continue;
                }
                long token = ++fencingSequence;
                ActionDispatchShape claimed = dispatch.withClaim(workerId, token, now.plus(leaseDuration));
                entry.setValue(claimed);
                return Optional.of(new ClaimedAction(workerId, dispatch.actionId(), token));
            }
            return Optional.empty();
        }

        private boolean claimable(ActionDispatchShape dispatch, Instant now) {
            if (dispatch.state().terminal() || dispatch.state() == ActionState.UNKNOWN
                    || dispatch.state() == ActionState.RECONCILING
                    || dispatch.state() == ActionState.MANUAL_REVIEW) {
                return false;
            }
            if (dispatch.state() == ActionState.RUNNING
                    && (dispatch.leaseUntil() == null || !now.isAfter(dispatch.leaseUntil()))) {
                return false;
            }
            return dispatch.action().dependencies().stream()
                    .allMatch(dependency -> action(dependency).state() == ActionState.SUCCEEDED);
        }

        private void markUnknown(ClaimedAction claim, String summary, Instant observedAt) {
            requireCurrentClaim(claim);
            ActionDispatchShape dispatch = action(claim.actionId());
            dispatches.put(claim.actionId(), dispatch.withState(ActionState.UNKNOWN));
            observe(claim.actionId(), "WORKER", summary, observedAt);
        }

        private void markReconciling(String actionId, Instant observedAt) {
            ActionDispatchShape dispatch = action(actionId);
            if (dispatch.state().terminal()) {
                return;
            }
            dispatches.put(actionId, dispatch.withState(ActionState.RECONCILING));
            observe(actionId, "RECONCILE", "query-started", observedAt);
        }

        private void markManualReview(String actionId, Instant observedAt) {
            ActionDispatchShape dispatch = action(actionId);
            if (!dispatch.state().terminal()) {
                dispatches.put(actionId, dispatch.withState(ActionState.MANUAL_REVIEW));
                observe(actionId, "RECONCILE", "result-remains-unprovable", observedAt);
            }
        }

        private void complete(ClaimedAction claim, ActionReceiptShape receipt) {
            requireCurrentClaim(claim);
            completeWithoutClaim(receipt,
                    receipt.result() == ResultKind.SUCCESS || receipt.result() == ResultKind.MANUAL_SUCCESS
                            ? ActionState.SUCCEEDED
                            : ActionState.FAILED);
        }

        private void completeReconcile(ActionReceiptShape receipt) {
            ActionDispatchShape dispatch = action(receipt.actionId());
            if (dispatch.state().terminal()) {
                ActionReceiptShape existing = receipts.get(receipt.actionId());
                if (existing != null && existing.externalKey().equals(receipt.externalKey())) {
                    return;
                }
                throw new IllegalStateException("Terminal action cannot accept another result");
            }
            completeWithoutClaim(receipt, receipt.result() == ResultKind.SUCCESS
                    ? ActionState.SUCCEEDED : ActionState.FAILED);
        }

        private void completeWithoutClaim(ActionReceiptShape receipt, ActionState state) {
            ActionReceiptShape existing = receipts.putIfAbsent(receipt.actionId(), receipt);
            if (existing != null && !existing.equals(receipt)) {
                throw new IllegalStateException("Action already has a different logical receipt");
            }
            ActionDispatchShape dispatch = action(receipt.actionId());
            dispatches.put(receipt.actionId(), dispatch.withState(state));
            observe(receipt.actionId(), "RECEIPT", receipt.evidence(), receipt.receivedAt());
            if (receipt.externalKey().startsWith("github:pr:")) {
                mergeExternalResult(
                        receipt.actionId(),
                        dispatch.action().parameters().get("connectionId"),
                        receipt.externalKey(),
                        "OPEN",
                        1,
                        receipt.receivedAt(),
                        false);
            }
        }

        private void resolveManually(
                String actionId,
                ManualResolution resolution,
                String actorId,
                String evidence,
                Instant resolvedAt) {
            if (evidence == null || evidence.isBlank()) {
                throw new IllegalArgumentException("Manual resolution requires evidence");
            }
            ActionDispatchShape dispatch = action(actionId);
            if (dispatch.state() != ActionState.MANUAL_REVIEW) {
                throw new IllegalStateException("Only manual-review actions can be resolved manually");
            }
            ResultKind result = resolution == ManualResolution.SUCCEEDED
                    ? ResultKind.MANUAL_SUCCESS : ResultKind.MANUAL_FAILURE;
            ActionState state = resolution == ManualResolution.SUCCEEDED
                    ? ActionState.MANUALLY_SUCCEEDED : ActionState.MANUALLY_FAILED;
            ActionReceiptShape receipt = new ActionReceiptShape(
                    actionId, "manual:" + actionId, result, evidence, resolvedAt);
            if (receipts.putIfAbsent(actionId, receipt) != null) {
                throw new IllegalStateException("Action already has a logical receipt");
            }
            dispatches.put(actionId, dispatch.withState(state));
            manualAudits.add(new ManualAuditShape(actionId, actorId, resolution, evidence, resolvedAt));
            observe(actionId, "MANUAL", evidence, resolvedAt);
        }

        private void mergeExternalResult(
                String actionId,
                String connectionId,
                String externalKey,
                String status,
                long providerVersion,
                Instant providerUpdatedAt,
                boolean manual) {
            String scopedKey = scopedExternalKey(connectionId, externalKey);
            externalResults.compute(scopedKey, (key, existing) -> existing == null
                    ? new ExternalResultShape(actionId, connectionId, externalKey, status,
                            providerVersion, providerUpdatedAt, manual)
                    : existing.merge(status, providerVersion, providerUpdatedAt, manual));
            observations.add(new ObservationShape(actionId, manual ? "MANUAL" : "PROVIDER",
                    status + ":v" + providerVersion, providerUpdatedAt));
        }

        private static String scopedExternalKey(String connectionId, String externalKey) {
            return connectionId + "|" + externalKey;
        }

        private void observe(String actionId, String source, String summary, Instant observedAt) {
            observations.add(new ObservationShape(actionId, source, summary, observedAt));
        }

        private void requireCurrentClaim(ClaimedAction claim) {
            ActionDispatchShape dispatch = action(claim.actionId());
            if (!Objects.equals(dispatch.owner(), claim.workerId())
                    || dispatch.fencingToken() != claim.fencingToken()) {
                throw new StaleFencingToken();
            }
        }

        private ActionDispatchShape action(String actionId) {
            return Optional.ofNullable(dispatches.get(actionId)).orElseThrow();
        }

        private PlannedActionShape plannedAction(String actionId) {
            return action(actionId).action();
        }

        private int dispatchCount() {
            return dispatches.size();
        }

        private int receiptCount() {
            return receipts.size();
        }

        private Optional<ActionReceiptShape> receipt(String actionId) {
            return Optional.ofNullable(receipts.get(actionId));
        }

        private List<ObservationShape> observationsFor(String actionId) {
            return observations.stream().filter(item -> item.actionId().equals(actionId)).toList();
        }

        private Optional<ExternalResultShape> externalResult(
                String connectionId, String externalKey) {
            return Optional.ofNullable(externalResults.get(
                    scopedExternalKey(connectionId, externalKey)));
        }

        private int externalResultCount() {
            return externalResults.size();
        }

        private int manualAuditCount() {
            return manualAudits.size();
        }
    }

    private static final class ActionWorkerProbe {

        private final String workerId = UUID.randomUUID().toString();
        private final DeliveryStore store;
        private final ExternalGitHubProbe provider;
        private final Duration leaseDuration;
        private ClaimedAction lastClaim;
        private final Map<String, Integer> inconclusiveQueries = new HashMap<>();

        private ActionWorkerProbe(
                DeliveryStore store, ExternalGitHubProbe provider, Duration leaseDuration) {
            this.store = store;
            this.provider = provider;
            this.leaseDuration = leaseDuration;
        }

        private boolean runNext(Instant now) {
            Optional<ClaimedAction> candidate = store.claim(workerId, now, leaseDuration);
            if (candidate.isEmpty()) {
                return false;
            }
            ClaimedAction claim = candidate.orElseThrow();
            lastClaim = claim;
            PlannedActionShape action = store.plannedAction(claim.actionId());
            if (action.kind() == ActionKind.PUSH_BRANCH) {
                executePush(claim, action, now);
            } else {
                executePullRequest(claim, action, now);
            }
            return true;
        }

        private void executePush(ClaimedAction claim, PlannedActionShape action, Instant now) {
            String branch = action.parameters().get("branch");
            String head = action.parameters().get("deliveryHead");
            if (provider.remoteHead(branch).filter(head::equals).isPresent()) {
                store.observe(action.id(), "RECONCILE", "remote-head-proves-push", now);
                store.complete(claim, success(action.id(), "push:" + branch + ":" + head,
                        "remote-head=" + head, now));
                return;
            }
            provider.push(branch, head);
            store.complete(claim, success(action.id(), "push:" + branch + ":" + head,
                    "push-response=" + head, now));
        }

        private void executePullRequest(ClaimedAction claim, PlannedActionShape action, Instant now) {
            try {
                ExternalPullRequest pullRequest = provider.createDraftPullRequest(action);
                store.complete(claim, success(action.id(), pullRequest.externalKey(),
                        "provider-response=" + pullRequest.number(), now));
            } catch (ResponseLostAfterWrite exception) {
                store.markUnknown(claim, "draft-pr-response-lost", now);
            }
        }

        private void reconcile(String actionId, Instant now) {
            ActionDispatchShape dispatch = store.action(actionId);
            if (dispatch.state().terminal()) {
                return;
            }
            store.markReconciling(actionId, now);
            PlannedActionShape action = dispatch.action();
            Optional<ExternalPullRequest> result = provider.findDraftPullRequest(action);
            if (result.isPresent()) {
                ExternalPullRequest pullRequest = result.orElseThrow();
                store.completeReconcile(success(actionId, pullRequest.externalKey(),
                        "query-proves-pr=" + pullRequest.number(), now));
                return;
            }
            int attempts = inconclusiveQueries.merge(actionId, 1, Integer::sum);
            store.observe(actionId, "RECONCILE", "query-inconclusive-" + attempts, now);
            if (attempts >= 2) {
                store.markManualReview(actionId, now);
            } else {
                store.dispatches.put(actionId, store.action(actionId).withState(ActionState.UNKNOWN));
            }
        }

        private Optional<ClaimedAction> lastClaim() {
            return Optional.ofNullable(lastClaim);
        }

        private static ActionReceiptShape success(
                String actionId, String externalKey, String evidence, Instant now) {
            return new ActionReceiptShape(actionId, externalKey, ResultKind.SUCCESS, evidence, now);
        }
    }

    private static final class ExternalGitHubProbe {

        private final Map<String, String> remoteHeads = new HashMap<>();
        private final Map<String, ExternalPullRequest> pullRequests = new HashMap<>();
        private int pushWrites;
        private int pullRequestWrites;
        private boolean exitAfterNextPush;
        private boolean loseNextPullRequestResponse;
        private boolean loseWithoutCreating;

        private void push(String branch, String head) {
            remoteHeads.put(branch, head);
            pushWrites++;
            if (exitAfterNextPush) {
                exitAfterNextPush = false;
                throw new SimulatedProcessExit();
            }
        }

        private ExternalPullRequest createDraftPullRequest(PlannedActionShape action) {
            String coordinate = pullRequestCoordinate(action);
            if (loseWithoutCreating) {
                loseWithoutCreating = false;
                throw new ResponseLostAfterWrite();
            }
            ExternalPullRequest result = pullRequests.computeIfAbsent(coordinate, ignored -> {
                pullRequestWrites++;
                return new ExternalPullRequest(42, "github:pr:42",
                        action.parameters().get("headSha"));
            });
            if (loseNextPullRequestResponse) {
                loseNextPullRequestResponse = false;
                throw new ResponseLostAfterWrite();
            }
            return result;
        }

        private Optional<ExternalPullRequest> findDraftPullRequest(PlannedActionShape action) {
            return Optional.ofNullable(pullRequests.get(pullRequestCoordinate(action)))
                    .filter(candidate -> candidate.headSha().equals(action.parameters().get("headSha")));
        }

        private static String pullRequestCoordinate(PlannedActionShape action) {
            return action.parameters().get("repositoryId") + ":"
                    + action.parameters().get("head") + ":" + action.parameters().get("base");
        }

        private Optional<String> remoteHead(String branch) {
            return Optional.ofNullable(remoteHeads.get(branch));
        }

        private void exitAfterNextPush() {
            exitAfterNextPush = true;
        }

        private void loseNextPullRequestResponse() {
            loseNextPullRequestResponse = true;
        }

        private void loseNextPullRequestResponseWithoutCreating() {
            loseWithoutCreating = true;
        }

        private int pushWrites() {
            return pushWrites;
        }

        private int pullRequestWrites() {
            return pullRequestWrites;
        }

        private int totalWrites() {
            return pushWrites + pullRequestWrites;
        }
    }

    private record ExternalPullRequest(int number, String externalKey, String headSha) {}

    private record WebhookEventShape(
            String connectionId,
            String actionId,
            String deliveryId,
            String externalKey,
            String providerStatus,
            long providerVersion,
            Instant providerUpdatedAt) {}

    private static final class WebhookReconcileProbe {

        private final DeliveryStore store;
        private final Set<DeliveryIdentity> acceptedDeliveries = new HashSet<>();

        private WebhookReconcileProbe(DeliveryStore store) {
            this.store = store;
        }

        private void accept(WebhookEventShape event) {
            if (!acceptedDeliveries.add(
                    new DeliveryIdentity(event.connectionId(), event.deliveryId()))) {
                return;
            }
            store.mergeExternalResult(event.actionId(), event.connectionId(), event.externalKey(),
                    event.providerStatus(), event.providerVersion(), event.providerUpdatedAt(), false);
        }

        private void mergeQuery(
                String connectionId,
                String actionId,
                String externalKey,
                String status,
                long version,
                Instant providerUpdatedAt) {
            store.mergeExternalResult(actionId, connectionId, externalKey, status, version,
                    providerUpdatedAt, false);
        }

        private int acceptedDeliveryCount() {
            return acceptedDeliveries.size();
        }
    }

    private record DeliveryIdentity(String connectionId, String deliveryId) {}

    private static final class SimulatedProcessExit extends RuntimeException {}

    private static final class ResponseLostAfterWrite extends RuntimeException {}

    private static final class StaleFencingToken extends RuntimeException {}

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte current : digest) {
                result.append(String.format("%02x", current));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }
}
