package io.crewscope.server.api;

import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.application.projection.ProjectionAdministrationCommandId;
import io.crewscope.domain.shared.id.OrganizationId;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Owns deterministic command identity derivation for the Operations HTTP boundary.
 * Keeping this mapping outside the controller prevents endpoint handlers from mixing
 * transport validation with idempotency semantics.
 */
final class OperationsCommandIdentity {

    private OperationsCommandIdentity() {}

    static ProjectionAdministrationCommandId projection(OrganizationId organizationId, String key) {
        return new ProjectionAdministrationCommandId(uuid("projection-administration", organizationId, key));
    }

    static UUID recovery(OrganizationId organizationId, String key) {
        return uuid("operations-recovery", organizationId, key);
    }

    private static UUID uuid(String namespace, OrganizationId organizationId, String key) {
        IdempotencyKey idempotencyKey = ApiHeaders.requireIdempotencyKey(key);
        String canonical = "io.crewscope/" + namespace + "/v1/"
                + organizationId + "/" + idempotencyKey.value();
        return UUID.nameUUIDFromBytes(canonical.getBytes(StandardCharsets.UTF_8));
    }
}
