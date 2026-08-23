package io.crewscope.infrastructure.github;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.crewscope.application.action.ExternalObservationRepository;
import io.crewscope.application.github.AcceptGitHubPullRequestWebhookRequest;
import io.crewscope.application.github.GitHubWebhookDisposition;
import io.crewscope.application.github.GitHubWebhookErrorCode;
import io.crewscope.application.github.GitHubWebhookException;
import io.crewscope.application.github.GitHubWebhookSecretResolver;
import io.crewscope.domain.action.ActionBundleDigest;
import io.crewscope.domain.action.ActionDigest;
import io.crewscope.domain.action.ExternalMergeOutcome;
import io.crewscope.domain.action.ExternalObjectStatus;
import io.crewscope.domain.action.ExternalObjectType;
import io.crewscope.domain.action.ExternalObservation;
import io.crewscope.domain.action.ExternalObservationKey;
import io.crewscope.domain.action.ExternalRepositoryId;
import io.crewscope.domain.action.ExternalResult;
import io.crewscope.domain.action.ExternalResultId;
import io.crewscope.domain.action.ExternalResultIdentity;
import io.crewscope.domain.action.ExternalResultSource;
import io.crewscope.domain.action.PlannedActionId;
import io.crewscope.domain.action.ActionBundleId;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.provider.ConnectionId;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.TaskFactHash;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkProjectId;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** M5-I10 proof for HMAC, Connection-scoped replay and monotonic PR state reconciliation. */
class GitHubPullRequestWebhookAdapterM5I10Test {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final byte[] SECRET =
            "m5-i10-webhook-high-entropy-secret".getBytes(StandardCharsets.UTF_8);
    private static final OrganizationId ORGANIZATION_ID = OrganizationId.generate();
    private static final ConnectionId CONNECTION_ID = new ConnectionId(
            UUID.fromString("00000000-0000-0000-0000-000000000910"));
    private static final PlannedActionId ACTION_ID = PlannedActionId.generate();
    private static final ActionDigest ACTION_DIGEST = new ActionDigest(
            TaskFactHash.sha256("m5-i10-action"));
    private static final ExternalResultIdentity IDENTITY = new ExternalResultIdentity(
            CONNECTION_ID, ExternalObjectType.PULL_REQUEST, "9001", "101:pull-request:42");
    private static final UtcTimestamp RECEIVED_AT = UtcTimestamp.parse(
            "2026-08-23T12:00:00Z");

    @Test
    void verifiesSignatureAndDeduplicatesOneDeliveryDurably() throws Exception {
        InMemoryObservations repository = new InMemoryObservations();
        GitHubPullRequestWebhookAdapter adapter = adapter(repository);
        byte[] opened = payload("opened", "open", false, "2026-08-23T11:00:00Z");

        var accepted = adapter.accept(request("delivery-1", opened, signature(opened)));
        var duplicate = adapter.accept(request("delivery-1", opened, signature(opened)));

        assertEquals(GitHubWebhookDisposition.ACCEPTED, accepted.disposition());
        assertEquals(GitHubWebhookDisposition.DUPLICATE, duplicate.disposition());
        assertEquals(ExternalObjectStatus.OPEN, accepted.observation().status());
        assertEquals(1, repository.values.size());

        byte[] conflicting = payload("closed", "closed", false, "2026-08-23T11:01:00Z");
        GitHubWebhookException conflict = assertThrows(
                GitHubWebhookException.class,
                () -> adapter.accept(request(
                        "delivery-1", conflicting, signature(conflicting))));
        assertEquals(GitHubWebhookErrorCode.DELIVERY_CONFLICT, conflict.code());
        assertEquals(1, repository.values.size());
    }

    @Test
    void rejectsForgeryAndRepositoryOrPullRequestIdentityDrift() throws Exception {
        InMemoryObservations repository = new InMemoryObservations();
        GitHubPullRequestWebhookAdapter adapter = adapter(repository);
        byte[] payload = payload("opened", "open", false, "2026-08-23T11:00:00Z");

        GitHubWebhookException forged = assertThrows(
                GitHubWebhookException.class,
                () -> adapter.accept(request("delivery-forged", payload, "sha256=" + "0".repeat(64))));
        assertEquals(GitHubWebhookErrorCode.SIGNATURE_INVALID, forged.code());

        byte[] wrongRepository = new ObjectMapper().writeValueAsBytes(java.util.Map.of(
                "action", "opened",
                "repository", java.util.Map.of("id", 202),
                "pull_request", pullRequest("open", false, "2026-08-23T11:00:00Z")));
        GitHubWebhookException drift = assertThrows(
                GitHubWebhookException.class,
                () -> adapter.accept(request(
                        "delivery-drift", wrongRepository, signature(wrongRepository))));
        assertEquals(GitHubWebhookErrorCode.IDENTITY_MISMATCH, drift.code());
        assertEquals(0, repository.values.size());
    }

    @Test
    void scopesTheSameProviderDeliveryIdByConnection() throws Exception {
        InMemoryObservations repository = new InMemoryObservations();
        GitHubPullRequestWebhookAdapter adapter = adapter(repository);
        byte[] payload = payload("opened", "open", false, "2026-08-23T11:00:00Z");
        adapter.accept(request("shared-delivery", payload, signature(payload)));
        ConnectionId otherConnection = new ConnectionId(
                UUID.fromString("00000000-0000-0000-0000-000000000911"));
        ExternalResultIdentity otherIdentity = new ExternalResultIdentity(
                otherConnection,
                ExternalObjectType.PULL_REQUEST,
                "9001",
                "101:pull-request:42");

        var other = adapter.accept(request(
                "shared-delivery", payload, signature(payload), otherIdentity));

        assertEquals(GitHubWebhookDisposition.ACCEPTED, other.disposition());
        assertEquals(2, repository.values.size());
    }

    @Test
    void closeReopenAndOutOfOrderObservationsConvergeMonotonically() throws Exception {
        InMemoryObservations repository = new InMemoryObservations();
        GitHubPullRequestWebhookAdapter adapter = adapter(repository);
        ExternalObservation opened = accept(adapter, "delivery-open", "opened", "open", false,
                "2026-08-23T11:00:00Z");
        ExternalObservation closed = accept(adapter, "delivery-close", "closed", "closed", false,
                "2026-08-23T11:03:00Z");
        ExternalObservation stale = accept(adapter, "delivery-stale", "synchronize", "open", false,
                "2026-08-23T11:02:00Z");
        ExternalObservation reopened = accept(adapter, "delivery-reopen", "reopened", "open", false,
                "2026-08-23T11:04:00Z");

        ReconcileFixture fixture = ReconcileFixture.create(opened);
        var closedMerge = fixture.result.merge(0, closed, Optional.empty(), fixture.actor);
        assertEquals(ExternalMergeOutcome.APPLIED, closedMerge.outcome());
        var staleMerge = closedMerge.result().merge(
                closedMerge.result().version(), stale, Optional.empty(), fixture.actor);
        assertEquals(ExternalMergeOutcome.STALE, staleMerge.outcome());
        var reopenMerge = staleMerge.result().merge(
                staleMerge.result().version(), reopened, Optional.empty(), fixture.actor);
        assertEquals(ExternalMergeOutcome.APPLIED, reopenMerge.outcome());
        assertEquals(ExternalObjectStatus.OPEN, reopenMerge.result().status());
        assertEquals(4, repository.values.size());
    }

    private static ExternalObservation accept(
            GitHubPullRequestWebhookAdapter adapter,
            String delivery,
            String action,
            String state,
            boolean merged,
            String updatedAt) throws Exception {
        byte[] payload = payload(action, state, merged, updatedAt);
        return adapter.accept(request(delivery, payload, signature(payload))).observation();
    }

    private static GitHubPullRequestWebhookAdapter adapter(InMemoryObservations repository) {
        return new GitHubPullRequestWebhookAdapter(
                JSON,
                new GitHubWebhookSecretResolver() {
                    @Override
                    public <T> T useSecret(
                            OrganizationId organizationId,
                            ConnectionId connectionId,
                            java.util.function.Function<byte[], T> operation) {
                        return operation.apply(SECRET.clone());
                    }
                },
                repository);
    }

    private static AcceptGitHubPullRequestWebhookRequest request(
            String deliveryId, byte[] payload, String signature) {
        return request(deliveryId, payload, signature, IDENTITY);
    }

    private static AcceptGitHubPullRequestWebhookRequest request(
            String deliveryId,
            byte[] payload,
            String signature,
            ExternalResultIdentity identity) {
        return new AcceptGitHubPullRequestWebhookRequest(
                ORGANIZATION_ID,
                ACTION_ID,
                ACTION_DIGEST,
                identity,
                new ExternalRepositoryId("101"),
                deliveryId,
                "pull_request",
                signature,
                payload,
                RECEIVED_AT);
    }

    private static byte[] payload(
            String action, String state, boolean merged, String updatedAt) throws Exception {
        return JSON.writeValueAsBytes(java.util.Map.of(
                "action", action,
                "repository", java.util.Map.of("id", 101),
                "pull_request", pullRequest(state, merged, updatedAt)));
    }

    private static java.util.Map<String, Object> pullRequest(
            String state, boolean merged, String updatedAt) {
        return java.util.Map.of(
                "id", 9001,
                "number", 42,
                "state", state,
                "merged", merged,
                "updated_at", updatedAt);
    }

    private static String signature(byte[] payload) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET, "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(payload));
    }

    private static final class InMemoryObservations implements ExternalObservationRepository {

        private final List<ExternalObservation> values = new ArrayList<>();

        @Override
        public boolean appendIfAbsent(
                OrganizationId organizationId, ExternalObservation observation) {
            if (exists(organizationId, observation.observationKey())) {
                return false;
            }
            values.add(observation);
            return true;
        }

        @Override
        public boolean exists(
                OrganizationId organizationId, ExternalObservationKey observationKey) {
            return values.stream().anyMatch(value ->
                    value.observationKey().equals(observationKey));
        }

        @Override
        public List<ExternalObservation> findObservationsByAction(
                OrganizationId organizationId, PlannedActionId actionId) {
            return values.stream().filter(value -> value.actionId().equals(actionId)).toList();
        }
    }

    private record ReconcileFixture(ExternalResult result, Principal actor) {

        static ReconcileFixture create(ExternalObservation opened) {
            TeamId teamId = TeamId.generate();
            PrincipalId actorId = PrincipalId.generate();
            Principal actor = Principal.create(
                    actorId,
                    PrincipalScope.team(ORGANIZATION_ID, teamId),
                    PrincipalType.SERVICE,
                    Optional.empty(),
                    "GitHub Reconciler",
                    Optional.empty(),
                    PrincipalVisibility.TEAM,
                    opened.observedAt());
            ExternalResult result = ExternalResult.reconstitute(
                    ExternalResultId.generate(),
                    new WorkItemScope(
                            ORGANIZATION_ID,
                            teamId,
                            WorkspaceId.generate(),
                            WorkProjectId.generate()),
                    ActionBundleId.generate(),
                    new ActionBundleDigest(TaskFactHash.sha256("m5-i10-bundle")),
                    ACTION_ID,
                    ACTION_DIGEST,
                    IDENTITY,
                    opened.status(),
                    opened.providerVersion(),
                    opened.providerUpdatedAt(),
                    ExternalResultSource.WEBHOOK,
                    opened.observationKey(),
                    opened.evidence(),
                    opened.observedAt(),
                    0,
                    AuditMetadata.createdBy(actorId, opened.observedAt()));
            return new ReconcileFixture(result, actor);
        }
    }
}
