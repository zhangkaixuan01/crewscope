package io.crewscope.domain.action;

import io.crewscope.domain.shared.error.DomainException;
import io.crewscope.domain.shared.error.DomainError;
import io.crewscope.domain.shared.error.DomainErrorCode;
import java.util.Map;
import java.util.Objects;

/** Raised before confirmation or dispatch when any pinned authority fact has drifted. */
public final class StaleActionBundleException extends DomainException {

    private final ActionInvalidationReason reason;

    public StaleActionBundleException(ActionInvalidationReason reason) {
        super(new DomainError(
                DomainErrorCode.INVALID_VALUE,
                "ActionBundle authority coordinates are stale",
                Map.of("reason", Objects.requireNonNull(reason, "reason").name())));
        this.reason = reason;
    }

    public ActionInvalidationReason reason() {
        return reason;
    }
}
