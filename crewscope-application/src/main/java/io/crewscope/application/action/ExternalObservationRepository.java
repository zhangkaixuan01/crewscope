package io.crewscope.application.action;

import io.crewscope.domain.action.ExternalObservation;
import io.crewscope.domain.action.ExternalObservationKey;
import io.crewscope.domain.action.PlannedActionId;
import io.crewscope.domain.shared.id.OrganizationId;
import java.util.List;

/** Append-only observation Port; Connection-scoped keys make Webhook replay idempotent. */
public interface ExternalObservationRepository {

    /** Returns false for an already committed observation key without rewriting history. */
    boolean appendIfAbsent(OrganizationId organizationId, ExternalObservation observation);

    boolean exists(OrganizationId organizationId, ExternalObservationKey observationKey);

    List<ExternalObservation> findByAction(
            OrganizationId organizationId, PlannedActionId actionId);
}
