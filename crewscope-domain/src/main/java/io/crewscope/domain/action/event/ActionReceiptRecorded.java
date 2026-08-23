package io.crewscope.domain.action.event;

import io.crewscope.domain.action.ActionReceipt;
import io.crewscope.domain.shared.DomainEvent;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Sanitized terminal Action result; external identities are represented only by a hash. */
public record ActionReceiptRecorded(
        UUID actionReceiptId,
        UUID actionBundleId,
        UUID plannedActionId,
        String actionDigest,
        String result,
        String source,
        Optional<String> externalIdentityHash,
        String evidenceCode,
        String evidenceHash,
        Optional<UUID> resolvedByPrincipalId) implements DomainEvent {

    public ActionReceiptRecorded {
        actionReceiptId = Objects.requireNonNull(actionReceiptId, "actionReceiptId");
        actionBundleId = Objects.requireNonNull(actionBundleId, "actionBundleId");
        plannedActionId = Objects.requireNonNull(plannedActionId, "plannedActionId");
        actionDigest = Objects.requireNonNull(actionDigest, "actionDigest");
        result = Objects.requireNonNull(result, "result");
        source = Objects.requireNonNull(source, "source");
        externalIdentityHash = Objects.requireNonNull(
                externalIdentityHash, "externalIdentityHash");
        evidenceCode = Objects.requireNonNull(evidenceCode, "evidenceCode");
        evidenceHash = Objects.requireNonNull(evidenceHash, "evidenceHash");
        resolvedByPrincipalId = Objects.requireNonNull(
                resolvedByPrincipalId, "resolvedByPrincipalId");
    }

    public static ActionReceiptRecorded from(ActionReceipt receipt) {
        ActionReceipt value = Objects.requireNonNull(receipt, "receipt");
        return new ActionReceiptRecorded(
                value.id().value(),
                value.bundleId().value(),
                value.actionId().value(),
                value.actionDigest().toString(),
                value.result().name(),
                value.source().name(),
                value.externalIdentity().map(identity -> identity.safeHash()),
                value.evidence().code(),
                value.evidence().evidenceHash().toString(),
                value.resolvedByPrincipalId().map(principalId -> principalId.value()));
    }
}
