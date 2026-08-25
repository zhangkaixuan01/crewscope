package io.crewscope.application.action;

import io.crewscope.application.github.CreateGitHubDraftPullRequestRequest;
import io.crewscope.application.github.GitHubAccessRequest;
import io.crewscope.application.github.GitHubBranchQueryResult;
import io.crewscope.application.github.GitHubDraftPullRequestPort;
import io.crewscope.application.github.GitHubDraftPullRequestException;
import io.crewscope.application.github.GitHubDraftPullRequestQueryResult;
import io.crewscope.application.github.GitHubDraftPullRequestResult;
import io.crewscope.application.github.GitHubPullRequestState;
import io.crewscope.application.github.GitHubProviderException;
import io.crewscope.application.github.GitHubPushPort;
import io.crewscope.application.github.GitHubPushException;
import io.crewscope.application.github.GitHubRepositoryPolicy;
import io.crewscope.application.github.PreflightGitHubRepositoryRequest;
import io.crewscope.application.github.PushGitHubBranchRequest;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.action.ActionAuthorityFacts;
import io.crewscope.domain.action.ActionBundle;
import io.crewscope.domain.action.ActionClaim;
import io.crewscope.domain.action.ActionDispatch;
import io.crewscope.domain.action.ActionDispatchStatus;
import io.crewscope.domain.action.ActionEvidenceReference;
import io.crewscope.domain.action.ActionReceipt;
import io.crewscope.domain.action.ActionReceiptId;
import io.crewscope.domain.action.ActionReceiptResult;
import io.crewscope.domain.action.ActionResultSource;
import io.crewscope.domain.action.ActionWorkerId;
import io.crewscope.domain.action.CreateDraftPullRequestActionParameters;
import io.crewscope.domain.action.ExternalMergeResult;
import io.crewscope.domain.action.ExternalObjectStatus;
import io.crewscope.domain.action.ExternalObjectType;
import io.crewscope.domain.action.ExternalObservation;
import io.crewscope.domain.action.ExternalObservationKey;
import io.crewscope.domain.action.ExternalResult;
import io.crewscope.domain.action.ExternalResultIdentity;
import io.crewscope.domain.action.ExternalResultSource;
import io.crewscope.domain.action.PlannedAction;
import io.crewscope.domain.action.PushBranchActionParameters;
import io.crewscope.domain.provider.ProviderAccessScope;
import io.crewscope.domain.provider.ProviderCapabilities;
import io.crewscope.domain.provider.ProviderResourceScope;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.error.DomainException;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Fenced query-only recovery Worker for UNKNOWN and expired external Action claims. */
public final class ActionReconciliationWorker {

    private static final ProviderCapabilities PUSH_CAPABILITY =
            ProviderCapabilities.of("source.repository.push");
    private static final ProviderCapabilities PR_CAPABILITY =
            ProviderCapabilities.of("source.pull-request.create");

    private final ActionDispatchRepository dispatches;
    private final ActionReceiptRepository receipts;
    private final ActionBundleRepository bundles;
    private final ConfirmationRepository confirmations;
    private final ExternalObservationRepository observations;
    private final ExternalResultMerger externalResults;
    private final ActionAuthorityFactsResolver authorityResolver;
    private final GitHubRepositoryPolicyResolver repositoryPolicyResolver;
    private final GitHubPushPort pushPort;
    private final GitHubDraftPullRequestPort pullRequestPort;
    private final ActionWorkerEventPublisher events;
    private final ActionReconciliationObserver observer;
    private final TransactionExecutor transactions;
    private final TimeProvider timeProvider;
    private final ActionWorkerId workerId;
    private final Duration leaseDuration;
    private final Duration retryDelay;
    private final Duration maximumUnknownAge;
    private final int maximumAttempts;
    private final int batchSize;

    public ActionReconciliationWorker(
            ActionDispatchRepository dispatches,
            ActionReceiptRepository receipts,
            ActionBundleRepository bundles,
            ConfirmationRepository confirmations,
            ExternalObservationRepository observations,
            ExternalResultMerger externalResults,
            ActionAuthorityFactsResolver authorityResolver,
            GitHubRepositoryPolicyResolver repositoryPolicyResolver,
            GitHubPushPort pushPort,
            GitHubDraftPullRequestPort pullRequestPort,
            ActionWorkerEventPublisher events,
            ActionReconciliationObserver observer,
            TransactionExecutor transactions,
            TimeProvider timeProvider,
            ActionWorkerId workerId,
            Duration leaseDuration,
            Duration retryDelay,
            Duration maximumUnknownAge,
            int maximumAttempts,
            int batchSize) {
        this.dispatches = Objects.requireNonNull(dispatches, "dispatches");
        this.receipts = Objects.requireNonNull(receipts, "receipts");
        this.bundles = Objects.requireNonNull(bundles, "bundles");
        this.confirmations = Objects.requireNonNull(confirmations, "confirmations");
        this.observations = Objects.requireNonNull(observations, "observations");
        this.externalResults = Objects.requireNonNull(externalResults, "externalResults");
        this.authorityResolver = Objects.requireNonNull(authorityResolver, "authorityResolver");
        this.repositoryPolicyResolver = Objects.requireNonNull(
                repositoryPolicyResolver, "repositoryPolicyResolver");
        this.pushPort = Objects.requireNonNull(pushPort, "pushPort");
        this.pullRequestPort = Objects.requireNonNull(pullRequestPort, "pullRequestPort");
        this.events = Objects.requireNonNull(events, "events");
        this.observer = Objects.requireNonNull(observer, "observer");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
        this.workerId = Objects.requireNonNull(workerId, "workerId");
        this.leaseDuration = bounded(
                leaseDuration, ActionClaim.MIN_LEASE, ActionClaim.MAX_LEASE, "leaseDuration");
        this.retryDelay = bounded(
                retryDelay, Duration.ofSeconds(1), Duration.ofMinutes(30), "retryDelay");
        this.maximumUnknownAge = bounded(
                maximumUnknownAge, Duration.ofMinutes(1), Duration.ofDays(7), "maximumUnknownAge");
        if (maximumAttempts < 1 || maximumAttempts > 100) {
            throw new IllegalArgumentException(
                    "Action reconciliation maximumAttempts must be between 1 and 100");
        }
        if (batchSize < 1 || batchSize > 100) {
            throw new IllegalArgumentException(
                    "Action reconciliation batchSize must be between 1 and 100");
        }
        this.maximumAttempts = maximumAttempts;
        this.batchSize = batchSize;
    }

    public ActionReconciliationBatchResult runOnce() {
        List<ActionReconciliationBatchResult> results = dispatches
                .findReconciliationOrganizations(timeProvider.now(), batchSize)
                .stream()
                .map(this::runOnce)
                .toList();
        return aggregate(results);
    }

    public ActionReconciliationBatchResult runOnce(OrganizationId organizationId) {
        OrganizationId organization = Objects.requireNonNull(organizationId, "organizationId");
        List<ActionReconciliationOutcome> outcomes = new ArrayList<>();
        for (int index = 0; index < batchSize; index++) {
            Optional<ClaimedReconciliation> claim = transactions.required(
                    () -> claimOne(organization));
            if (claim.isEmpty()) {
                break;
            }
            ClaimedReconciliation value = claim.orElseThrow();
            Instant started = Instant.now();
            ActionReconciliationOutcome outcome;
            try {
                outcome = reconcile(value);
            } catch (RuntimeException failure) {
                outcome = ActionReconciliationOutcome.FAILED;
                record(value, outcome, started);
                throw failure;
            }
            outcomes.add(outcome);
            record(value, outcome, started);
        }
        return batch(outcomes);
    }

    private Optional<ClaimedReconciliation> claimOne(OrganizationId organizationId) {
        UtcTimestamp now = timeProvider.now();
        for (ActionDispatch candidate : dispatches.lockReconciliationCandidates(
                organizationId, now, batchSize)) {
            ActionBundle bundle = requireBundle(organizationId, candidate);
            PlannedAction action = action(bundle, candidate);
            var confirmation = confirmations.findById(
                            organizationId, candidate.confirmationId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Action Confirmation is unavailable for reconciliation"));
            ActionDispatch claimed = candidate.claimForReconciliation(
                    candidate.version(),
                    bundle,
                    confirmation,
                    dependencyReceipts(organizationId, candidate),
                    workerId,
                    now,
                    plus(now, leaseDuration));
            ActionDispatch committed = dispatches.update(claimed);
            events.dispatchTransitioned(committed, bundle, correlation(action));
            return Optional.of(new ClaimedReconciliation(committed, bundle, action));
        }
        return Optional.empty();
    }

    private ActionReconciliationOutcome reconcile(ClaimedReconciliation claimed) {
        Optional<ActionReconciliationOutcome> webhookConclusion =
                mergeCommittedObservations(claimed);
        if (webhookConclusion.isPresent()) {
            return webhookConclusion.orElseThrow();
        }

        ActionAuthorityFacts facts;
        try {
            facts = authorityResolver.resolveCurrent(claimed.bundle().authority());
        } catch (DomainException unavailable) {
            return inconclusive(claimed);
        }
        try {
            return switch (claimed.action().kind()) {
                case PUSH_BRANCH -> reconcilePush(claimed, facts);
                case CREATE_DRAFT_PR -> reconcilePullRequest(claimed, facts);
                case NOTIFY_COLLABORATION -> throw new IllegalStateException(
                        "M5 Action reconciliation cannot inspect collaboration notifications");
            };
        } catch (GitHubPushException
                | GitHubDraftPullRequestException
                | GitHubProviderException providerUnavailable) {
            // Query-only failure cannot create a duplicate side effect; the bounded state machine
            // retains UNKNOWN and eventually exposes the Action in the human queue.
            return inconclusive(claimed);
        }
    }

    private Optional<ActionReconciliationOutcome> mergeCommittedObservations(
            ClaimedReconciliation claimed) {
        List<ExternalObservation> committed = observations.findObservationsByAction(
                claimed.dispatch().scope().organizationId(), claimed.action().id());
        if (committed.isEmpty()) {
            return Optional.empty();
        }
        return transactions.required(() -> {
            ActionDispatch current = requireCurrentClaim(claimed, timeProvider.now());
            ExternalMergeResult last = null;
            for (ExternalObservation observation : committed) {
                last = externalResults.merge(current, claimed.bundle(), claimed.action(), observation);
            }
            if (last == null || claimed.action().kind()
                    != io.crewscope.domain.action.ActionKind.CREATE_DRAFT_PR) {
                return Optional.empty();
            }
            ExternalResult result = last.result();
            if (!result.status().supports(ExternalObjectType.PULL_REQUEST)
                    || result.lastSource() == ExternalResultSource.WRITE_RESPONSE) {
                return Optional.empty();
            }
            return Optional.of(completeFromObservation(current, claimed, result));
        });
    }

    private ActionReconciliationOutcome reconcilePush(
            ClaimedReconciliation claimed, ActionAuthorityFacts facts) {
        PushBranchActionParameters parameters =
                (PushBranchActionParameters) claimed.action().parameters();
        GitHubRepositoryPolicy policy = repositoryPolicyResolver.resolve(facts, claimed.action());
        GitHubBranchQueryResult query = pushPort.queryBranch(new PushGitHubBranchRequest(
                claimed.dispatch().scope(),
                preflight(
                        claimed,
                        facts,
                        parameters.repositoryId().value(),
                        PUSH_CAPABILITY,
                        policy),
                claimed.bundle().authority().providerAuthorization(),
                claimed.bundle().authority().targetPrecondition(),
                parameters));
        ExternalResultIdentity identity = branchIdentity(parameters);
        ExternalObservation observation = new ExternalObservation(
                observationKey(
                        parameters.connectionId(),
                        claimed,
                        query.remoteHead().map(value -> value.value()).orElse("MISSING")),
                claimed.action().id(),
                claimed.action().digest(),
                identity,
                query.remoteHead().isPresent()
                        ? ExternalObjectStatus.PRESENT
                        : ExternalObjectStatus.MISSING,
                Optional.empty(),
                Optional.of(query.observedAt()),
                ExternalResultSource.ACTIVE_QUERY,
                ActionEvidenceReference.hashed(
                        "GITHUB_BRANCH_ACTIVE_QUERY",
                        identity.safeHash() + '\n'
                                + query.remoteHead().map(value -> value.value()).orElse("MISSING")),
                query.observedAt());
        if (query.remoteHead().filter(parameters.deliveryHead()::equals).isPresent()) {
            return completeFromActiveQuery(
                    claimed,
                    observation,
                    identity,
                    parameters.deliveryHead().value());
        }
        return mergeAndInconclusive(claimed, observation);
    }

    private ActionReconciliationOutcome reconcilePullRequest(
            ClaimedReconciliation claimed, ActionAuthorityFacts facts) {
        CreateDraftPullRequestActionParameters parameters =
                (CreateDraftPullRequestActionParameters) claimed.action().parameters();
        GitHubRepositoryPolicy policy = repositoryPolicyResolver.resolve(facts, claimed.action());
        GitHubDraftPullRequestQueryResult query = pullRequestPort.queryDraft(
                new CreateGitHubDraftPullRequestRequest(
                        claimed.dispatch().scope(),
                        preflight(
                                claimed,
                                facts,
                                parameters.repositoryId().value(),
                                PR_CAPABILITY,
                                policy),
                        claimed.bundle().authority().providerAuthorization(),
                        claimed.bundle().authority().targetPrecondition(),
                        parameters));
        if (query.pullRequest().isEmpty()) {
            return inconclusive(claimed);
        }
        GitHubDraftPullRequestResult pullRequest = query.pullRequest().orElseThrow();
        ExternalObservation observation = new ExternalObservation(
                observationKey(
                        parameters.connectionId(),
                        claimed,
                        pullRequest.pullRequestId() + ':' + pullRequest.providerUpdatedAt()),
                claimed.action().id(),
                claimed.action().digest(),
                pullRequest.externalIdentity(),
                status(pullRequest.state()),
                Optional.empty(),
                Optional.of(pullRequest.providerUpdatedAt()),
                ExternalResultSource.ACTIVE_QUERY,
                ActionEvidenceReference.hashed(
                        "GITHUB_DRAFT_PR_ACTIVE_QUERY",
                        pullRequest.externalIdentity().safeHash() + '\n'
                                + pullRequest.headSha().value() + '\n'
                                + pullRequest.state().name() + '\n'
                                + pullRequest.providerUpdatedAt()),
                query.observedAt());
        String targetVersion = pullRequest.headSha().value() + ':'
                + pullRequest.state().name() + ':' + pullRequest.providerUpdatedAt();
        return completeFromActiveQuery(
                claimed, observation, pullRequest.externalIdentity(), targetVersion);
    }

    private ActionReconciliationOutcome completeFromActiveQuery(
            ClaimedReconciliation claimed,
            ExternalObservation observation,
            ExternalResultIdentity identity,
            String targetVersion) {
        return transactions.required(() -> {
            UtcTimestamp now = timeProvider.now();
            ActionDispatch current = requireCurrentClaim(claimed, now);
            externalResults.merge(current, claimed.bundle(), claimed.action(), observation);
            ActionClaim claim = current.claim().orElseThrow();
            ActionReceipt candidate = ActionReceipt.fromClaim(
                    ActionReceiptId.generate(),
                    current,
                    claimed.action(),
                    claim,
                    ActionReceiptResult.SUCCEEDED,
                    ActionResultSource.ACTIVE_QUERY,
                    Optional.of(identity),
                    Optional.of(targetVersion),
                    observation.evidence(),
                    now);
            return commitReceipt(current, claimed, candidate, true);
        });
    }

    private ActionReconciliationOutcome completeFromObservation(
            ActionDispatch current,
            ClaimedReconciliation claimed,
            ExternalResult result) {
        UtcTimestamp now = timeProvider.now();
        ActionResultSource source = switch (result.lastSource()) {
            case WEBHOOK -> ActionResultSource.WEBHOOK;
            case ACTIVE_QUERY -> ActionResultSource.ACTIVE_QUERY;
            case WRITE_RESPONSE -> throw new IllegalStateException(
                    "Write response cannot conclude unclaimed observation recovery");
        };
        CreateDraftPullRequestActionParameters parameters =
                (CreateDraftPullRequestActionParameters) claimed.action().parameters();
        String providerCoordinate = result.providerVersion().map(String::valueOf)
                .orElseGet(() -> result.providerUpdatedAt().orElseThrow().toString());
        String targetVersion = parameters.headSha().value() + ':'
                + result.status().name() + ':' + providerCoordinate;
        ActionReceipt candidate = ActionReceipt.fromObservation(
                ActionReceiptId.generate(),
                current,
                claimed.action(),
                ActionReceiptResult.SUCCEEDED,
                source,
                Optional.of(result.identity()),
                Optional.of(targetVersion),
                result.lastEvidence(),
                now);
        return commitReceipt(current, claimed, candidate, false);
    }

    private ActionReconciliationOutcome commitReceipt(
            ActionDispatch current,
            ClaimedReconciliation claimed,
            ActionReceipt candidate,
            boolean claimedReceipt) {
        ActionReceiptInsertResult inserted = receipts.insertIfAbsent(candidate);
        ActionDispatch completed = claimedReceipt
                ? current.completeClaimed(
                        current.version(),
                        current.claim().orElseThrow(),
                        inserted.receipt(),
                        candidate.receivedAt())
                : current.completeFromObservation(
                        current.version(), inserted.receipt(), candidate.receivedAt());
        ActionDispatch committed = dispatches.update(completed);
        if (inserted.inserted()) {
            events.receiptRecorded(inserted.receipt(), claimed.bundle(), correlation(claimed.action()));
        }
        events.dispatchTransitioned(committed, claimed.bundle(), correlation(claimed.action()));
        return ActionReconciliationOutcome.SUCCEEDED;
    }

    private ActionReconciliationOutcome mergeAndInconclusive(
            ClaimedReconciliation claimed, ExternalObservation observation) {
        return transactions.required(() -> {
            ActionDispatch current = requireCurrentClaim(claimed, timeProvider.now());
            externalResults.merge(current, claimed.bundle(), claimed.action(), observation);
            return recordInconclusive(current, claimed);
        });
    }

    private ActionReconciliationOutcome inconclusive(ClaimedReconciliation claimed) {
        return transactions.required(() -> recordInconclusive(
                requireCurrentClaim(claimed, timeProvider.now()), claimed));
    }

    private ActionReconciliationOutcome recordInconclusive(
            ActionDispatch current, ClaimedReconciliation claimed) {
        UtcTimestamp now = timeProvider.now();
        boolean ageExhausted = Duration.between(
                        current.audit().createdAt().value(), now.value())
                .compareTo(maximumUnknownAge) >= 0;
        int limit = ageExhausted
                ? Math.addExact(current.reconciliationAttempts(), 1)
                : maximumAttempts;
        Duration delay = retryDelay.multipliedBy(Math.min(
                Math.addExact(current.reconciliationAttempts(), 1), 10));
        ActionDispatch next = current.recordInconclusiveReconciliation(
                current.version(),
                current.claim().orElseThrow(),
                limit,
                plus(now, delay),
                now);
        ActionDispatch committed = dispatches.update(next);
        events.dispatchTransitioned(committed, claimed.bundle(), correlation(claimed.action()));
        return committed.status() == ActionDispatchStatus.MANUAL_REVIEW
                ? ActionReconciliationOutcome.MANUAL_REVIEW
                : ActionReconciliationOutcome.INCONCLUSIVE;
    }

    private ActionDispatch requireCurrentClaim(
            ClaimedReconciliation claimed, UtcTimestamp now) {
        ActionDispatch current = dispatches.findById(
                        claimed.dispatch().scope().organizationId(), claimed.dispatch().id())
                .orElseThrow(() -> new IllegalStateException("Action Dispatch is unavailable"));
        ActionClaim expected = claimed.dispatch().claim().orElseThrow();
        if (current.claim().filter(expected::equals).isEmpty() || !expected.isActiveAt(now)) {
            throw new IllegalStateException("Action reconciliation claim is no longer current");
        }
        return current;
    }

    private PreflightGitHubRepositoryRequest preflight(
            ClaimedReconciliation claimed,
            ActionAuthorityFacts facts,
            String externalRepositoryId,
            ProviderCapabilities capability,
            GitHubRepositoryPolicy policy) {
        var authority = claimed.bundle().authority();
        var provider = authority.providerAuthorization();
        GitHubAccessRequest access = new GitHubAccessRequest(
                claimed.dispatch().scope().organizationId(),
                provider.connectionId(),
                provider.connectionVersion(),
                provider.grantId(),
                provider.grantVersion(),
                facts.connectionGrant().grantee(),
                new ProviderAccessScope(
                        capability,
                        ProviderResourceScope.of("repository:" + externalRepositoryId)),
                authority.responsibility().actorPrincipalId(),
                correlation(claimed.action()));
        return new PreflightGitHubRepositoryRequest(
                access,
                externalRepositoryId,
                authority.targetPrecondition().defaultBranch(),
                policy);
    }

    private ActionBundle requireBundle(
            OrganizationId organizationId, ActionDispatch dispatch) {
        ActionBundle bundle = bundles.findById(organizationId, dispatch.bundleId())
                .orElseThrow(() -> new IllegalStateException(
                        "Action Bundle is unavailable for reconciliation"));
        if (!bundle.digest().equals(dispatch.bundleDigest())) {
            throw new IllegalStateException("Action Bundle digest changed during reconciliation");
        }
        return bundle;
    }

    private List<ActionReceipt> dependencyReceipts(
            OrganizationId organizationId, ActionDispatch dispatch) {
        List<ActionReceipt> result = new ArrayList<>();
        dispatch.dependencies().forEach(dependency -> receipts.findReceiptByAction(
                        organizationId, dependency.predecessorActionId())
                .ifPresent(result::add));
        return List.copyOf(result);
    }

    private static PlannedAction action(ActionBundle bundle, ActionDispatch dispatch) {
        return bundle.actions().stream()
                .filter(value -> value.id().equals(dispatch.actionId())
                        && value.digest().equals(dispatch.actionDigest()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Action is absent from its confirmed Bundle"));
    }

    private static ExternalResultIdentity branchIdentity(PushBranchActionParameters parameters) {
        String coordinate = parameters.repositoryId().value()
                + ":branch:" + parameters.branch().value();
        return new ExternalResultIdentity(
                parameters.connectionId(), ExternalObjectType.BRANCH, coordinate, coordinate);
    }

    private static ExternalObservationKey observationKey(
            io.crewscope.domain.provider.ConnectionId connectionId,
            ClaimedReconciliation claimed,
            String providerCoordinate) {
        return ExternalObservationKey.derive(
                connectionId,
                ExternalResultSource.ACTIVE_QUERY,
                claimed.action().id() + ":"
                        + claimed.dispatch().claim().orElseThrow()
                                .fencingToken().value()
                        + ":" + providerCoordinate);
    }

    private static ExternalObjectStatus status(GitHubPullRequestState state) {
        return switch (state) {
            case OPEN -> ExternalObjectStatus.OPEN;
            case CLOSED -> ExternalObjectStatus.CLOSED;
            case MERGED -> ExternalObjectStatus.MERGED;
        };
    }

    private void record(
            ClaimedReconciliation claimed,
            ActionReconciliationOutcome outcome,
            Instant started) {
        var authority = claimed.bundle().authority();
        observer.record(
                new ActionReconciliationTrace(
                        claimed.dispatch().scope().organizationId(),
                        claimed.dispatch().scope().teamId(),
                        authority.taskExecutionId(),
                        authority.reviewDecision().id(),
                        claimed.action().id(),
                        claimed.action().kind(),
                        claimed.dispatch().claim().orElseThrow().mode()),
                outcome,
                Duration.between(started, Instant.now()));
    }

    private static ActionReconciliationBatchResult batch(
            List<ActionReconciliationOutcome> outcomes) {
        int succeeded = count(outcomes, ActionReconciliationOutcome.SUCCEEDED);
        int inconclusive = count(outcomes, ActionReconciliationOutcome.INCONCLUSIVE);
        int manual = count(outcomes, ActionReconciliationOutcome.MANUAL_REVIEW);
        int failed = outcomes.size() - succeeded - inconclusive - manual;
        return new ActionReconciliationBatchResult(
                outcomes.size(), succeeded, inconclusive, manual, failed);
    }

    private static ActionReconciliationBatchResult aggregate(
            List<ActionReconciliationBatchResult> values) {
        return new ActionReconciliationBatchResult(
                values.stream().mapToInt(ActionReconciliationBatchResult::claimed).sum(),
                values.stream().mapToInt(ActionReconciliationBatchResult::succeeded).sum(),
                values.stream().mapToInt(ActionReconciliationBatchResult::inconclusive).sum(),
                values.stream().mapToInt(ActionReconciliationBatchResult::manualReview).sum(),
                values.stream().mapToInt(ActionReconciliationBatchResult::failed).sum());
    }

    private static int count(
            List<ActionReconciliationOutcome> values,
            ActionReconciliationOutcome expected) {
        return Math.toIntExact(values.stream().filter(expected::equals).count());
    }

    private static UUID correlation(PlannedAction action) {
        return action.id().value();
    }

    private static UtcTimestamp plus(UtcTimestamp value, Duration duration) {
        return UtcTimestamp.from(value.value().plus(duration));
    }

    private static Duration bounded(
            Duration value, Duration minimum, Duration maximum, String field) {
        Duration required = Objects.requireNonNull(value, field);
        if (required.compareTo(minimum) < 0 || required.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(
                    "Action reconciliation " + field + " is outside its supported range");
        }
        return required;
    }

    private record ClaimedReconciliation(
            ActionDispatch dispatch, ActionBundle bundle, PlannedAction action) {}
}
