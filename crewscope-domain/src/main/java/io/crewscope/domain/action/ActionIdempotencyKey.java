package io.crewscope.domain.action;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.task.TaskFactHash;
import java.util.Objects;

/** Server-derived stable idempotency key for one exact action digest. */
public record ActionIdempotencyKey(TaskFactHash value) {

    public ActionIdempotencyKey {
        value = Objects.requireNonNull(value, "value");
    }

    public static ActionIdempotencyKey derive(
            OrganizationId organizationId,
            ActionBundleId bundleId,
            PlannedActionId actionId,
            ActionDigest actionDigest) {
        ActionCanonicalEncoder encoder = new ActionCanonicalEncoder("action-idempotency-v1")
                .add(Objects.requireNonNull(organizationId, "organizationId").toString())
                .add(Objects.requireNonNull(bundleId, "bundleId").toString())
                .add(Objects.requireNonNull(actionId, "actionId").toString())
                .add(Objects.requireNonNull(actionDigest, "actionDigest").toString());
        return new ActionIdempotencyKey(encoder.digest());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
