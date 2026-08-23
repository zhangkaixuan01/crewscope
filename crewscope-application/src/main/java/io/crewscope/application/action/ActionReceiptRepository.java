package io.crewscope.application.action;

import io.crewscope.domain.action.ActionReceipt;
import io.crewscope.domain.action.ActionReceiptId;
import io.crewscope.domain.action.ExternalResultIdentity;
import io.crewscope.domain.action.PlannedActionId;
import io.crewscope.domain.shared.id.OrganizationId;
import java.util.Optional;

/** Append-only Receipt Port with logical-action and external-business-key uniqueness. */
public interface ActionReceiptRepository {

    ActionReceiptInsertResult insertIfAbsent(ActionReceipt receipt);

    Optional<ActionReceipt> findById(OrganizationId organizationId, ActionReceiptId id);

    Optional<ActionReceipt> findReceiptByAction(
            OrganizationId organizationId, PlannedActionId actionId);

    Optional<ActionReceipt> findByExternalIdentity(
            OrganizationId organizationId, ExternalResultIdentity identity);
}
