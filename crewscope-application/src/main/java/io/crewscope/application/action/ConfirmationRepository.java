package io.crewscope.application.action;

import io.crewscope.domain.action.ActionBundleId;
import io.crewscope.domain.action.Confirmation;
import io.crewscope.domain.action.ConfirmationId;
import io.crewscope.domain.shared.id.OrganizationId;
import java.util.Optional;

/** Persistence Port for exact human ActionBundle authorizations. */
public interface ConfirmationRepository {

    Confirmation insert(Confirmation confirmation);

    Confirmation update(Confirmation confirmation);

    Optional<Confirmation> findById(OrganizationId organizationId, ConfirmationId id);

    Optional<Confirmation> findByBundle(
            OrganizationId organizationId, ActionBundleId bundleId);

    Optional<Confirmation> findActiveByBundle(
            OrganizationId organizationId, ActionBundleId bundleId);
}
