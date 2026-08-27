package io.crewscope.application.operations;

import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.shared.id.OrganizationId;
import java.util.Objects;

/** Administrator request for one closed-set, strongly confirmed recovery action. */
public record OperationsRecoveryCommand(
        OperationsRecoveryCommandId commandId,
        OrganizationId organizationId,
        OperationsRecoveryTarget target,
        TeamAccessContext access,
        OperationsRecoveryStrongConfirmation confirmation) {

    public OperationsRecoveryCommand {
        commandId = Objects.requireNonNull(commandId, "commandId");
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        target = Objects.requireNonNull(target, "target");
        access = Objects.requireNonNull(access, "access");
        confirmation = Objects.requireNonNull(confirmation, "confirmation");
        confirmation.require(target);
    }
}
