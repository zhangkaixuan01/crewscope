package io.crewscope.application.operations;

import io.crewscope.domain.shared.id.OrganizationId;
import java.util.Optional;

/**
 * Atomic persistence Port for operations recovery.
 *
 * <p>The M6-I02 adapter must lock and compare the target's expected version, schedule exactly one
 * replay or redelivery, store the receipt, append a sanitized DomainEvent and project its Audit
 * record in one transaction. Existing DeadLetter and delivery history remains immutable. A
 * concurrent insert for the same Organization and Command ID must resolve inside that transaction:
 * the same fingerprint returns the committed receipt, while another fingerprint raises an
 * idempotency conflict without scheduling another recovery fact.
 */
public interface OperationsRecoveryRepository {

    Optional<OperationsRecoveryReceipt> findReceipt(
            OrganizationId organizationId, OperationsRecoveryCommandId commandId);

    /** Atomically resolves idempotency and commits at most one recovery schedule. */
    OperationsRecoveryReceipt recover(OperationsRecoveryRequest request);
}
