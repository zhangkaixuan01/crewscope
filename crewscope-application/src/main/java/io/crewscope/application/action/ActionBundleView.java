package io.crewscope.application.action;

import io.crewscope.domain.action.ActionBundle;
import io.crewscope.domain.action.ActionDispatch;
import io.crewscope.domain.action.ActionReceipt;
import io.crewscope.domain.action.Confirmation;
import io.crewscope.domain.action.CreateDraftPullRequestActionParameters;
import io.crewscope.domain.action.ExternalResult;
import io.crewscope.domain.action.PlannedAction;
import io.crewscope.domain.action.PushBranchActionParameters;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Safe Control/Conversation projection of one immutable ActionBundle and its delivery state. */
public record ActionBundleView(
        String id,
        long version,
        String digest,
        String validity,
        String staleReason,
        String taskId,
        String taskExecutionId,
        String reviewDecisionId,
        String repositoryBindingId,
        String repositoryKey,
        String baselineCommit,
        String deliveryCommit,
        ConfirmationView confirmation,
        List<PlannedActionView> actions) {

    public ActionBundleView {
        actions = List.copyOf(Objects.requireNonNull(actions, "actions"));
    }

    public static ActionBundleView from(
            ActionBundle bundle,
            Optional<Confirmation> confirmation,
            List<ActionDispatch> dispatches,
            List<ActionReceipt> receipts,
            List<ExternalResult> externalResults,
            Optional<String> staleReason) {
        ActionBundle value = Objects.requireNonNull(bundle, "bundle");
        Map<io.crewscope.domain.action.PlannedActionId, ActionDispatch> dispatchByAction =
                dispatches.stream().collect(Collectors.toUnmodifiableMap(
                        ActionDispatch::actionId, Function.identity()));
        Map<io.crewscope.domain.action.PlannedActionId, ActionReceipt> receiptByAction =
                receipts.stream().collect(Collectors.toUnmodifiableMap(
                        ActionReceipt::actionId, Function.identity()));
        Map<io.crewscope.domain.action.PlannedActionId, ExternalResult> resultByAction =
                externalResults.stream().collect(Collectors.toUnmodifiableMap(
                        ExternalResult::actionId, Function.identity()));
        var target = value.authority().targetPrecondition();
        return new ActionBundleView(
                value.id().toString(),
                value.version(),
                value.digest().toString(),
                staleReason.isEmpty() ? "CURRENT" : "STALE",
                staleReason.orElse(null),
                value.authority().taskId().toString(),
                value.authority().taskExecutionId().toString(),
                value.authority().reviewDecision().id().toString(),
                target.repositoryBindingId().toString(),
                target.repositoryKey().value(),
                target.baselineCommit().value(),
                target.deliveryCommit().value(),
                confirmation.map(ConfirmationView::from).orElse(null),
                value.actions().stream()
                        .map(action -> PlannedActionView.from(
                                action,
                                Optional.ofNullable(dispatchByAction.get(action.id())),
                                Optional.ofNullable(receiptByAction.get(action.id())),
                                Optional.ofNullable(resultByAction.get(action.id()))))
                        .toList());
    }

    public record ConfirmationView(
            String id,
            long version,
            String status,
            String confirmedByPrincipalId,
            String confirmedAt,
            String validUntil,
            String cancellationReason) {

        static ConfirmationView from(Confirmation value) {
            return new ConfirmationView(
                    value.id().toString(),
                    value.version(),
                    value.status().name(),
                    value.confirmedByPrincipalId().toString(),
                    value.confirmedAt().toString(),
                    value.validUntil().toString(),
                    value.cancellationReason().map(Enum::name).orElse(null));
        }
    }

    public record PlannedActionView(
            String id,
            int sequence,
            String kind,
            String risk,
            String digest,
            String validUntil,
            List<String> dependencyActionIds,
            ActionParameterView parameters,
            DispatchView dispatch,
            ActionReceiptView receipt,
            ExternalResultView externalResult) {

        static PlannedActionView from(
                PlannedAction action,
                Optional<ActionDispatch> dispatch,
                Optional<ActionReceipt> receipt,
                Optional<ExternalResult> result) {
            return new PlannedActionView(
                    action.id().toString(),
                    action.sequence(),
                    action.kind().name(),
                    action.risk().name(),
                    action.digest().toString(),
                    action.validUntil().toString(),
                    action.dependencies().stream()
                            .map(value -> value.predecessorActionId().toString())
                            .toList(),
                    ActionParameterView.from(action),
                    dispatch.map(DispatchView::from).orElse(null),
                    receipt.map(ActionReceiptView::from).orElse(null),
                    result.map(ExternalResultView::from).orElse(null));
        }
    }

    /** Exact user-reviewed parameters without Connection, Credential or internal endpoint fields. */
    public record ActionParameterView(
            String repositoryId,
            String branch,
            String deliveryHead,
            String expectedRemoteHead,
            String pullRequestHead,
            String pullRequestBase,
            String pullRequestHeadSha,
            String title,
            String body,
            Boolean draft) {

        static ActionParameterView from(PlannedAction action) {
            if (action.parameters() instanceof PushBranchActionParameters push) {
                return new ActionParameterView(
                        push.repositoryId().value(),
                        push.branch().value(),
                        push.deliveryHead().value(),
                        push.expectedRemoteHead().map(value -> value.value()).orElse(null),
                        null, null, null, null, null, null);
            }
            if (action.parameters() instanceof CreateDraftPullRequestActionParameters pullRequest) {
                return new ActionParameterView(
                        pullRequest.repositoryId().value(),
                        null, null, null,
                        pullRequest.head().value(),
                        pullRequest.base().value(),
                        pullRequest.headSha().value(),
                        pullRequest.title(),
                        pullRequest.body(),
                        pullRequest.draft());
            }
            throw new IllegalStateException("Unsupported public PlannedAction parameters");
        }
    }

    /** Scheduler summary excludes Worker IDs, Lease coordinates, fencing tokens and idempotency keys. */
    public record DispatchView(
            String id,
            long version,
            String status,
            int claimAttempts,
            int reconciliationAttempts,
            String nextAttemptAt,
            String cancellationReason,
            String compensationDisposition) {

        static DispatchView from(ActionDispatch value) {
            return new DispatchView(
                    value.id().toString(),
                    value.version(),
                    value.status().name(),
                    value.claimAttempts(),
                    value.reconciliationAttempts(),
                    value.notBefore().toString(),
                    value.cancellationReason().map(Enum::name).orElse(null),
                    value.compensationDisposition().name());
        }
    }

    /** Logical external side-effect result with only a hash of the Provider business identity. */
    public record ActionReceiptView(
            String id,
            String result,
            String source,
            String externalObjectType,
            String externalIdentityHash,
            String targetVersion,
            String evidenceCode,
            String manualReason,
            String receivedAt) {

        static ActionReceiptView from(ActionReceipt value) {
            return new ActionReceiptView(
                    value.id().toString(),
                    value.result().name(),
                    value.source().name(),
                    value.externalIdentity().map(identity -> identity.objectType().name()).orElse(null),
                    value.externalIdentity().map(identity -> identity.safeHash()).orElse(null),
                    value.targetVersion().orElse(null),
                    value.evidence().code(),
                    value.manualReason().map(Enum::name).orElse(null),
                    value.receivedAt().toString());
        }
    }

    /** Monotonic external projection without raw Provider object IDs or observation keys. */
    public record ExternalResultView(
            String status,
            String externalObjectType,
            String externalIdentityHash,
            Long providerVersion,
            String providerUpdatedAt,
            String source,
            String observedAt,
            long version) {

        static ExternalResultView from(ExternalResult value) {
            return new ExternalResultView(
                    value.status().name(),
                    value.identity().objectType().name(),
                    value.identity().safeHash(),
                    value.providerVersion().orElse(null),
                    value.providerUpdatedAt().map(Object::toString).orElse(null),
                    value.lastSource().name(),
                    value.observedAt().toString(),
                    value.version());
        }
    }
}
