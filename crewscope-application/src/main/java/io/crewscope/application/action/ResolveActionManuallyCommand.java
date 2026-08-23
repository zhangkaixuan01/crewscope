package io.crewscope.application.action;

import io.crewscope.domain.action.ActionDispatchId;
import io.crewscope.domain.action.ActionReceiptResult;
import io.crewscope.domain.action.ExternalResultIdentity;
import io.crewscope.domain.action.ManualResolutionReason;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.id.OrganizationId;
import java.util.Objects;
import java.util.Optional;

/** Strong-version human conclusion for one Action already escalated to MANUAL_REVIEW. */
public record ResolveActionManuallyCommand(
        OrganizationId organizationId,
        ActionDispatchId dispatchId,
        long expectedVersion,
        ActionReceiptResult result,
        Optional<ExternalResultIdentity> externalIdentity,
        Optional<String> targetVersion,
        ManualResolutionReason reason,
        String explanation,
        Principal actor) {

    public ResolveActionManuallyCommand {
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        dispatchId = Objects.requireNonNull(dispatchId, "dispatchId");
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("Manual Action expectedVersion must not be negative");
        }
        result = Objects.requireNonNull(result, "result");
        if (!result.isManual()) {
            throw new IllegalArgumentException("Manual Action result must be a manual terminal result");
        }
        externalIdentity = Objects.requireNonNull(externalIdentity, "externalIdentity");
        targetVersion = Objects.requireNonNull(targetVersion, "targetVersion")
                .map(String::strip);
        if (externalIdentity.isPresent() != targetVersion.isPresent()) {
            throw new IllegalArgumentException(
                    "Manual Action external identity and target version must be paired");
        }
        reason = Objects.requireNonNull(reason, "reason");
        if (explanation == null || explanation.isBlank() || explanation.strip().length() > 2_000) {
            throw new IllegalArgumentException(
                    "Manual Action explanation must contain 1 to 2000 characters");
        }
        explanation = explanation.strip();
        actor = Objects.requireNonNull(actor, "actor");
    }
}
