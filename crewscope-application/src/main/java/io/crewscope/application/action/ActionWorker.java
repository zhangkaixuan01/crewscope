package io.crewscope.application.action;

import io.crewscope.application.github.CreateGitHubDraftPullRequestRequest;
import io.crewscope.application.github.GitHubAccessRequest;
import io.crewscope.application.github.GitHubDraftPullRequestErrorCode;
import io.crewscope.application.github.GitHubDraftPullRequestException;
import io.crewscope.application.github.GitHubDraftPullRequestPort;
import io.crewscope.application.github.GitHubDraftPullRequestResult;
import io.crewscope.application.github.GitHubProviderErrorCode;
import io.crewscope.application.github.GitHubProviderException;
import io.crewscope.application.github.GitHubPushErrorCode;
import io.crewscope.application.github.GitHubPushException;
import io.crewscope.application.github.GitHubPushPort;
import io.crewscope.application.github.GitHubPushResult;
import io.crewscope.application.github.GitHubRepositoryPolicy;
import io.crewscope.application.github.PreflightGitHubRepositoryRequest;
import io.crewscope.application.github.PushGitHubBranchRequest;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.action.ActionBundle;
import io.crewscope.domain.action.ActionClaim;
import io.crewscope.domain.action.ActionDispatch;
import io.crewscope.domain.action.ActionDispatchStatus;
import io.crewscope.domain.action.ActionEvidenceReference;
import io.crewscope.domain.action.ActionReceipt;
import io.crewscope.domain.action.ActionReceiptId;
import io.crewscope.domain.action.ActionReceiptResult;
import io.crewscope.domain.action.ActionResultSource;
import io.crewscope.domain.action.ActionRetryDirective;
import io.crewscope.domain.action.ActionWorkerId;
import io.crewscope.domain.action.CreateDraftPullRequestActionParameters;
import io.crewscope.domain.action.ExternalObjectType;
import io.crewscope.domain.action.ExternalResultIdentity;
import io.crewscope.domain.action.PlannedAction;
import io.crewscope.domain.action.PushBranchActionParameters;
import io.crewscope.domain.provider.ProviderAccessScope;
import io.crewscope.domain.provider.ProviderCapabilities;
import io.crewscope.domain.provider.ProviderResourceScope;
import io.crewscope.domain.shared.error.DomainException;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Bounded confirmed-Action Worker. Database claims and receipts are transactional; Provider calls
 * always execute after the claim transaction has committed.
 */
public final class ActionWorker {

    private static final ProviderCapabilities PUSH_CAPABILITY =
            ProviderCapabilities.of("source.repository.push");
    private static final ProviderCapabilities PR_CAPABILITY =
            ProviderCapabilities.of("source.pull-request.create");

    private final ActionDispatchRepository dispatches;
    private final ActionReceiptRepository receipts;
    private final ActionBundleRepository bundles;
    private final ConfirmationRepository confirmations;
    private final ActionAuthorityFactsResolver authorityResolver;
    private final GitHubRepositoryPolicyResolver repositoryPolicyResolver;
    private final GitHubPushPort pushPort;
    private final GitHubDraftPullRequestPort pullRequestPort;
    private final ActionWorkerEventPublisher events;
    private final TransactionExecutor transactions;
    private final TimeProvider timeProvider;
    private final ActionWorkerId workerId;
    private final Duration leaseDuration;
    private final Duration retryDelay;
    private final int batchSize;

    public ActionWorker(
            ActionDispatchRepository dispatches,
            ActionReceiptRepository receipts,
            ActionBundleRepository bundles,
            ConfirmationRepository confirmations,
            ActionAuthorityFactsResolver authorityResolver,
            GitHubRepositoryPolicyResolver repositoryPolicyResolver,
            GitHubPushPort pushPort,
            GitHubDraftPullRequestPort pullRequestPort,
            ActionWorkerEventPublisher events,
            TransactionExecutor transactions,
            TimeProvider timeProvider,
            ActionWorkerId workerId,
            Duration leaseDuration,
            Duration retryDelay,
            int batchSize) {
        this.dispatches = Objects.requireNonNull(dispatches, "dispatches");
        this.receipts = Objects.requireNonNull(receipts, "receipts");
        this.bundles = Objects.requireNonNull(bundles, "bundles");
        this.confirmations = Objects.requireNonNull(confirmations, "confirmations");
        this.authorityResolver = Objects.requireNonNull(authorityResolver, "authorityResolver");
        this.repositoryPolicyResolver = Objects.requireNonNull(
                repositoryPolicyResolver, "repositoryPolicyResolver");
        this.pushPort = Objects.requireNonNull(pushPort, "pushPort");
        this.pullRequestPort = Objects.requireNonNull(pullRequestPort, "pullRequestPort");
        this.events = Objects.requireNonNull(events, "events");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
        this.workerId = Objects.requireNonNull(workerId, "workerId");
        this.leaseDuration = bounded(
                leaseDuration, ActionClaim.MIN_LEASE, ActionClaim.MAX_LEASE, "leaseDuration");
        this.retryDelay = bounded(
                retryDelay, Duration.ofSeconds(1), Duration.ofMinutes(2), "retryDelay");
        if (batchSize < 1 || batchSize > 100) {
            throw new IllegalArgumentException("Action Worker batchSize must be between 1 and 100");
        }
        this.batchSize = batchSize;
    }

    /** Polls and executes at most the configured number of committed READY actions. */
    public ActionWorkerBatchResult runOnce(OrganizationId organizationId) {
        OrganizationId requiredOrganization = Objects.requireNonNull(
                organizationId, "organizationId");
        List<ExecutionOutcome> outcomes = new ArrayList<>();
        for (int index = 0; index < batchSize; index++) {
            Optional<ClaimedAction> claimed = transactions.required(
                    () -> claimOne(requiredOrganization));
            if (claimed.isEmpty()) {
                break;
            }
            outcomes.add(execute(claimed.orElseThrow()));
        }
        int succeeded = count(outcomes, ExecutionOutcome.SUCCEEDED);
        int failed = count(outcomes, ExecutionOutcome.FAILED);
        int unknown = count(outcomes, ExecutionOutcome.UNKNOWN);
        int rescheduled = count(outcomes, ExecutionOutcome.RESCHEDULED);
        return new ActionWorkerBatchResult(
                outcomes.size(), succeeded, failed, unknown, rescheduled);
    }

    /** Polls a bounded tenant set without ever holding locks across organizations. */
    public ActionWorkerBatchResult runOnce() {
        List<ActionWorkerBatchResult> results = dispatches.findClaimableOrganizations(
                        timeProvider.now(), batchSize).stream()
                .map(this::runOnce)
                .toList();
        return new ActionWorkerBatchResult(
                results.stream().mapToInt(ActionWorkerBatchResult::claimed).sum(),
                results.stream().mapToInt(ActionWorkerBatchResult::succeeded).sum(),
                results.stream().mapToInt(ActionWorkerBatchResult::failed).sum(),
                results.stream().mapToInt(ActionWorkerBatchResult::unknown).sum(),
                results.stream().mapToInt(ActionWorkerBatchResult::rescheduled).sum());
    }

    private Optional<ClaimedAction> claimOne(OrganizationId organizationId) {
        UtcTimestamp now = timeProvider.now();
        UtcTimestamp leaseUntil = plus(now, leaseDuration);
        for (ActionDispatch candidate : dispatches.lockClaimable(
                organizationId, now, batchSize)) {
            // I11 performs writes only from READY. UNKNOWN and expired claims belong to I12.
            if (candidate.status() != ActionDispatchStatus.READY) {
                continue;
            }
            ActionBundle bundle;
            io.crewscope.domain.action.ActionAuthorityFacts facts;
            ActionDispatch claimed;
            PlannedAction action;
            try {
                bundle = requireBundle(organizationId, candidate);
                var confirmation = confirmations.findById(
                                organizationId, candidate.confirmationId())
                        .orElseThrow(() -> unavailable("Action Confirmation"));
                facts = authorityResolver.resolveCurrent(bundle.authority());
                List<ActionReceipt> dependencyReceipts = dependencyReceipts(
                        organizationId, candidate);
                claimed = candidate.claim(
                        candidate.version(),
                        bundle,
                        facts,
                        confirmation,
                        dependencyReceipts,
                        workerId,
                        now,
                        leaseUntil);
                action = action(bundle, candidate);
            } catch (DomainException unavailableOrBlocked) {
                // A locked but currently unauthorized/dependency-blocked row remains durable READY.
                // A07 cancellation and later current-fact changes can make it eligible again.
                continue;
            }
            // Persistence/event failures must escape so the outer transaction rolls the claim back.
            ActionDispatch committed = dispatches.update(claimed);
            events.dispatchTransitioned(committed, bundle, correlation(candidate));
            return Optional.of(new ClaimedAction(committed, bundle, action, facts));
        }
        return Optional.empty();
    }

    private ExecutionOutcome execute(ClaimedAction claimed) {
        return switch (claimed.action().kind()) {
            case PUSH_BRANCH -> executePush(claimed);
            case CREATE_DRAFT_PR -> executePullRequest(claimed);
        };
    }

    private ExecutionOutcome executePush(ClaimedAction claimed) {
        PushBranchActionParameters parameters = (PushBranchActionParameters) claimed.action().parameters();
        GitHubRepositoryPolicy policy = repositoryPolicyResolver.resolve(
                claimed.facts(), claimed.action());
        PushGitHubBranchRequest request = new PushGitHubBranchRequest(
                claimed.dispatch().scope(),
                preflight(claimed, parameters.repositoryId().value(), PUSH_CAPABILITY, policy),
                claimed.bundle().authority().providerAuthorization(),
                claimed.bundle().authority().targetPrecondition(),
                parameters);
        GitHubPushResult result;
        try {
            result = pushPort.pushBranch(request);
        } catch (GitHubPushException failure) {
            return handlePushFailure(claimed, failure);
        } catch (GitHubProviderException failure) {
            return handleProviderFailure(claimed, failure);
        } catch (RuntimeException uncertain) {
            // Only failures raised from inside the Provider invocation can have crossed its write
            // boundary. Planning, policy and receipt failures remain visible to the platform.
            return markUnknown(claimed);
        }
        ExternalResultIdentity identity = new ExternalResultIdentity(
                parameters.connectionId(),
                ExternalObjectType.BRANCH,
                parameters.repositoryId().value() + ":branch:" + parameters.branch().value(),
                parameters.repositoryId().value() + ":branch:" + parameters.branch().value());
        return succeed(
                claimed,
                identity,
                result.deliveryHead().value(),
                "GITHUB_PUSH_" + result.outcome().name(),
                result.repositoryId().value() + '\n'
                        + result.branch().value() + '\n'
                        + result.deliveryHead().value());
    }

    private ExecutionOutcome executePullRequest(ClaimedAction claimed) {
        CreateDraftPullRequestActionParameters parameters =
                (CreateDraftPullRequestActionParameters) claimed.action().parameters();
        GitHubRepositoryPolicy policy = repositoryPolicyResolver.resolve(
                claimed.facts(), claimed.action());
        CreateGitHubDraftPullRequestRequest request = new CreateGitHubDraftPullRequestRequest(
                claimed.dispatch().scope(),
                preflight(claimed, parameters.repositoryId().value(), PR_CAPABILITY, policy),
                claimed.bundle().authority().providerAuthorization(),
                claimed.bundle().authority().targetPrecondition(),
                parameters);
        GitHubDraftPullRequestResult result;
        try {
            result = pullRequestPort.ensureDraft(request);
        } catch (GitHubDraftPullRequestException failure) {
            return handlePullRequestFailure(claimed, failure);
        } catch (GitHubProviderException failure) {
            return handleProviderFailure(claimed, failure);
        } catch (RuntimeException uncertain) {
            // An unclassified failure inside ensureDraft may follow a successful external write.
            return markUnknown(claimed);
        }
        String targetVersion = result.headSha().value() + ':'
                + result.state().name() + ':' + result.providerUpdatedAt();
        return succeed(
                claimed,
                result.externalIdentity(),
                targetVersion,
                "GITHUB_DRAFT_PR_" + result.outcome().name(),
                result.externalIdentity().safeHash() + '\n'
                        + result.headSha().value() + '\n'
                        + result.titleHash() + '\n'
                        + result.bodyHash() + '\n'
                        + result.state().name() + '\n'
                        + result.providerUpdatedAt());
    }

    private PreflightGitHubRepositoryRequest preflight(
            ClaimedAction claimed,
            String externalRepositoryId,
            ProviderCapabilities capability,
            GitHubRepositoryPolicy policy) {
        var authority = claimed.bundle().authority();
        var provider = authority.providerAuthorization();
        ProviderAccessScope requested = new ProviderAccessScope(
                capability,
                ProviderResourceScope.of("repository:" + externalRepositoryId));
        GitHubAccessRequest access = new GitHubAccessRequest(
                claimed.dispatch().scope().organizationId(),
                provider.connectionId(),
                provider.connectionVersion(),
                provider.grantId(),
                provider.grantVersion(),
                claimed.facts().connectionGrant().grantee(),
                requested,
                claimed.bundle().authority().responsibility().actorPrincipalId(),
                correlation(claimed.dispatch()));
        return new PreflightGitHubRepositoryRequest(
                access,
                externalRepositoryId,
                authority.targetPrecondition().defaultBranch(),
                policy);
    }

    private ExecutionOutcome handlePushFailure(
            ClaimedAction claimed, GitHubPushException failure) {
        if (failure.code() == GitHubPushErrorCode.UNKNOWN) {
            return markUnknown(claimed);
        }
        if (failure.code() == GitHubPushErrorCode.MIRROR_UNAVAILABLE) {
            return reschedule(claimed, "GITHUB_PUSH_" + failure.code().name());
        }
        return fail(claimed, "GITHUB_PUSH_" + failure.code().name());
    }

    private ExecutionOutcome handlePullRequestFailure(
            ClaimedAction claimed, GitHubDraftPullRequestException failure) {
        if (failure.code() == GitHubDraftPullRequestErrorCode.UNKNOWN) {
            return markUnknown(claimed);
        }
        if (failure.code() == GitHubDraftPullRequestErrorCode.RATE_LIMITED
                || failure.code() == GitHubDraftPullRequestErrorCode.PROVIDER_UNAVAILABLE) {
            return reschedule(claimed, "GITHUB_DRAFT_PR_" + failure.code().name());
        }
        return fail(claimed, "GITHUB_DRAFT_PR_" + failure.code().name());
    }

    private ExecutionOutcome handleProviderFailure(
            ClaimedAction claimed, GitHubProviderException failure) {
        return retryableProvider(failure.code())
                ? reschedule(claimed, "GITHUB_PROVIDER_" + failure.code().name())
                : fail(claimed, "GITHUB_PROVIDER_" + failure.code().name());
    }

    private ExecutionOutcome succeed(
            ClaimedAction claimed,
            ExternalResultIdentity identity,
            String targetVersion,
            String evidenceCode,
            String canonicalEvidence) {
        return complete(
                claimed,
                ActionReceiptResult.SUCCEEDED,
                Optional.of(identity),
                Optional.of(targetVersion),
                ActionEvidenceReference.hashed(evidenceCode, canonicalEvidence),
                ExecutionOutcome.SUCCEEDED);
    }

    private ExecutionOutcome fail(ClaimedAction claimed, String evidenceCode) {
        return complete(
                claimed,
                ActionReceiptResult.FAILED,
                Optional.empty(),
                Optional.empty(),
                ActionEvidenceReference.hashed(
                        evidenceCode,
                        claimed.dispatch().actionDigest().toString() + '\n' + evidenceCode),
                ExecutionOutcome.FAILED);
    }

    private ExecutionOutcome complete(
            ClaimedAction claimed,
            ActionReceiptResult result,
            Optional<ExternalResultIdentity> identity,
            Optional<String> targetVersion,
            ActionEvidenceReference evidence,
            ExecutionOutcome outcome) {
        return transactions.required(() -> {
            UtcTimestamp now = timeProvider.now();
            ActionDispatch current = requireCurrentClaim(claimed, now);
            ActionClaim claim = current.claim().orElseThrow();
            ActionReceipt candidate = ActionReceipt.fromClaim(
                    ActionReceiptId.generate(),
                    current,
                    claimed.action(),
                    claim,
                    result,
                    ActionResultSource.WRITE_RESPONSE,
                    identity,
                    targetVersion,
                    evidence,
                    now);
            ActionReceiptInsertResult inserted = receipts.insertIfAbsent(candidate);
            ActionReceipt receipt = inserted.receipt();
            ActionDispatch completed = current.completeClaimed(
                    current.version(), claim, receipt, now);
            ActionDispatch committed = dispatches.update(completed);
            if (inserted.inserted()) {
                events.receiptRecorded(receipt, claimed.bundle(), correlation(current));
            }
            events.dispatchTransitioned(committed, claimed.bundle(), correlation(current));
            return outcome;
        });
    }

    private ExecutionOutcome markUnknown(ClaimedAction claimed) {
        return transactions.required(() -> {
            UtcTimestamp now = timeProvider.now();
            ActionDispatch current = requireCurrentClaim(claimed, now);
            ActionDispatch unknown = current.markUnknown(
                    current.version(), current.claim().orElseThrow(), now);
            ActionDispatch committed = dispatches.update(unknown);
            events.dispatchTransitioned(committed, claimed.bundle(), correlation(current));
            return ExecutionOutcome.UNKNOWN;
        });
    }

    private ExecutionOutcome reschedule(ClaimedAction claimed, String reasonCode) {
        return transactions.required(() -> {
            UtcTimestamp now = timeProvider.now();
            ActionDispatch current = requireCurrentClaim(claimed, now);
            UtcTimestamp notBefore = plus(now, retryDelay);
            if (notBefore.compareTo(current.validUntil()) >= 0) {
                return failWithinTransaction(
                        claimed, current, now, reasonCode + "_WINDOW_EXHAUSTED");
            }
            ActionRetryDirective directive = new ActionRetryDirective(
                    ActionEvidenceReference.hashed(
                            "NO_SIDE_EFFECT_" + reasonCode,
                            current.actionDigest().toString() + '\n' + reasonCode),
                    notBefore);
            ActionDispatch retry = current.scheduleRetry(
                    current.version(), current.claim().orElseThrow(), directive, now);
            ActionDispatch committed = dispatches.update(retry);
            events.dispatchTransitioned(committed, claimed.bundle(), correlation(current));
            return ExecutionOutcome.RESCHEDULED;
        });
    }

    private ExecutionOutcome failWithinTransaction(
            ClaimedAction claimed,
            ActionDispatch current,
            UtcTimestamp now,
            String evidenceCode) {
        ActionClaim claim = current.claim().orElseThrow();
        ActionReceipt candidate = ActionReceipt.fromClaim(
                ActionReceiptId.generate(),
                current,
                claimed.action(),
                claim,
                ActionReceiptResult.FAILED,
                ActionResultSource.WRITE_RESPONSE,
                Optional.empty(),
                Optional.empty(),
                ActionEvidenceReference.hashed(
                        evidenceCode,
                        current.actionDigest().toString() + '\n' + evidenceCode),
                now);
        ActionReceiptInsertResult inserted = receipts.insertIfAbsent(candidate);
        ActionDispatch completed = current.completeClaimed(
                current.version(), claim, inserted.receipt(), now);
        ActionDispatch committed = dispatches.update(completed);
        if (inserted.inserted()) {
            events.receiptRecorded(inserted.receipt(), claimed.bundle(), correlation(current));
        }
        events.dispatchTransitioned(committed, claimed.bundle(), correlation(current));
        return ExecutionOutcome.FAILED;
    }

    private ActionDispatch requireCurrentClaim(ClaimedAction claimed, UtcTimestamp now) {
        ActionDispatch current = dispatches.findById(
                        claimed.dispatch().scope().organizationId(), claimed.dispatch().id())
                .orElseThrow(() -> new IllegalStateException("Action Dispatch is unavailable"));
        ActionClaim expected = claimed.dispatch().claim().orElseThrow();
        if (current.claim().filter(expected::equals).isEmpty() || !expected.isActiveAt(now)) {
            throw new IllegalStateException("Action Worker claim is no longer current");
        }
        return current;
    }

    private ActionBundle requireBundle(
            OrganizationId organizationId, ActionDispatch dispatch) {
        ActionBundle bundle = bundles.findById(organizationId, dispatch.bundleId())
                .orElseThrow(() -> unavailable("Action Bundle"));
        if (!bundle.digest().equals(dispatch.bundleDigest())) {
            throw unavailable("Action Bundle digest");
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
                .orElseThrow(() -> unavailable(
                        "Action Dispatch membership in its Bundle"));
    }

    private static boolean retryableProvider(GitHubProviderErrorCode code) {
        return code == GitHubProviderErrorCode.RATE_LIMITED
                || code == GitHubProviderErrorCode.PROVIDER_UNAVAILABLE;
    }

    private static DomainValidationException unavailable(String fact) {
        return new DomainValidationException("actionDispatch.authority", fact + " is unavailable");
    }

    private static int count(List<ExecutionOutcome> outcomes, ExecutionOutcome expected) {
        return Math.toIntExact(outcomes.stream().filter(expected::equals).count());
    }

    private static UUID correlation(ActionDispatch dispatch) {
        return dispatch.actionId().value();
    }

    private static UtcTimestamp plus(UtcTimestamp value, Duration duration) {
        return UtcTimestamp.from(value.value().plus(duration));
    }

    private static Duration bounded(
            Duration value, Duration minimum, Duration maximum, String field) {
        Duration required = Objects.requireNonNull(value, field);
        if (required.compareTo(minimum) < 0 || required.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(
                    "Action Worker " + field + " is outside its supported range");
        }
        return required;
    }

    private enum ExecutionOutcome {
        SUCCEEDED,
        FAILED,
        UNKNOWN,
        RESCHEDULED
    }

    private record ClaimedAction(
            ActionDispatch dispatch,
            ActionBundle bundle,
            PlannedAction action,
            io.crewscope.domain.action.ActionAuthorityFacts facts) {}
}
