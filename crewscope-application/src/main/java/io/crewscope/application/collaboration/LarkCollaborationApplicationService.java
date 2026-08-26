package io.crewscope.application.collaboration;

import io.crewscope.domain.collaboration.LarkConnectionAuthorization;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;

/** Coordinates administrator-only Lark authorization Preflight and live health checks. */
public final class LarkCollaborationApplicationService {

    private final LarkConnectionAuthorizationResolver authorizations;
    private final LarkMappingAdministration administration;
    private final LarkProviderHealthPort healthPort;
    private final TimeProvider timeProvider;

    public LarkCollaborationApplicationService(
            LarkConnectionAuthorizationResolver authorizations,
            LarkMappingAdministration administration,
            LarkProviderHealthPort healthPort,
            TimeProvider timeProvider) {
        this.authorizations = Objects.requireNonNull(authorizations, "authorizations");
        this.administration = Objects.requireNonNull(administration, "administration");
        this.healthPort = Objects.requireNonNull(healthPort, "healthPort");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
    }

    /** Requires the complete current authorization graph and one successful live tenant query. */
    public LarkConnectionPreflightResult preflight(LarkConnectionPreflightCommand command) {
        LarkConnectionPreflightCommand required = requireAdministrator(command);
        LarkConnectionAuthorization authorization = authorizations.resolveCurrent(
                required.organizationId(),
                required.teamId(),
                required.providerBindingId(),
                required.requiredCapabilities());
        LarkProviderHealth health = healthPort.checkHealth(
                authorization, required.actor().id());
        if (!health.healthy()) {
            throw new LarkConnectionPreflightException(health);
        }
        return LarkConnectionPreflightResult.from(authorization, health.checkedAt());
    }

    /** Returns safe unavailable health when current Binding authorization cannot be resolved. */
    public LarkProviderHealth health(LarkConnectionPreflightCommand command) {
        LarkConnectionPreflightCommand required = requireAdministrator(command);
        try {
            LarkConnectionAuthorization authorization = authorizations.resolveCurrent(
                    required.organizationId(),
                    required.teamId(),
                    required.providerBindingId(),
                    required.requiredCapabilities());
            return healthPort.checkHealth(authorization, required.actor().id());
        } catch (DomainValidationException ignored) {
            return LarkProviderHealth.authorizationUnavailable(timeProvider.now());
        }
    }

    private LarkConnectionPreflightCommand requireAdministrator(
            LarkConnectionPreflightCommand command) {
        LarkConnectionPreflightCommand required = Objects.requireNonNull(command, "command");
        UtcTimestamp now = timeProvider.now();
        administration.requireProviderAdministrator(
                required.organizationId(), required.teamId(), required.actor(), now);
        return required;
    }
}
