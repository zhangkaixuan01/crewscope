package io.crewscope.application.operations;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.id.OrganizationId;
import java.util.Objects;

/** Administrator request for one closed-set, strongly confirmed recovery action. */
public record OperationsRecoveryCommand(
        OperationsRecoveryCommandId commandId,
        OrganizationId organizationId,
        OperationsRecoveryTarget target,
        Principal actor,
        OperationsRecoveryStrongConfirmation confirmation) {

    public OperationsRecoveryCommand {
        commandId = Objects.requireNonNull(commandId, "commandId");
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        target = Objects.requireNonNull(target, "target");
        actor = Objects.requireNonNull(actor, "actor");
        confirmation = Objects.requireNonNull(confirmation, "confirmation");
        confirmation.require(target);
    }
}
