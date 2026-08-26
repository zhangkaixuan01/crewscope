package io.crewscope.application.collaboration;

import io.crewscope.application.provider.ProviderBindingCandidate;
import io.crewscope.application.provider.ProviderBindingResolver;
import io.crewscope.domain.collaboration.LarkConnectionAuthorization;
import io.crewscope.domain.collaboration.LarkCollaborationCapabilities;
import io.crewscope.domain.collaboration.LarkTenantKey;
import io.crewscope.domain.provider.Connection;
import io.crewscope.domain.provider.ConnectionGrant;
import io.crewscope.domain.provider.ProviderBinding;
import io.crewscope.domain.provider.ProviderBindingId;
import io.crewscope.domain.provider.ProviderCapabilities;
import io.crewscope.domain.provider.ProviderOwnerType;
import io.crewscope.domain.provider.ProviderType;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import java.util.Objects;

/** ADR-006 adapter that narrows one explicit current Binding to the Lark Team contract. */
public final class DefaultLarkConnectionAuthorizationResolver
        implements LarkConnectionAuthorizationResolver {

    public static final String IMPLEMENTATION_KEY = LarkCollaborationCapabilities.CONNECTOR_KEY;

    private final ProviderBindingResolver bindings;

    public DefaultLarkConnectionAuthorizationResolver(ProviderBindingResolver bindings) {
        this.bindings = Objects.requireNonNull(bindings, "bindings");
    }

    @Override
    public LarkConnectionAuthorization resolveCurrent(
            OrganizationId organizationId,
            TeamId teamId,
            ProviderBindingId providerBindingId,
            ProviderCapabilities requiredCapabilities) {
        OrganizationId organization = Objects.requireNonNull(
                organizationId, "organizationId");
        TeamId team = Objects.requireNonNull(teamId, "teamId");
        ProviderCapabilities capabilities = Objects.requireNonNull(
                requiredCapabilities, "requiredCapabilities");
        ProviderBindingCandidate candidate = bindings.resolveCurrent(
                        organization, Objects.requireNonNull(providerBindingId, "providerBindingId"))
                .orElseThrow(DefaultLarkConnectionAuthorizationResolver::unavailable);
        ProviderBinding binding = candidate.binding();
        if (binding.providerType() != ProviderType.COLLABORATION
                || candidate.implementation().type() != ProviderType.COLLABORATION
                || !IMPLEMENTATION_KEY.equals(candidate.implementation().key())
                || !binding.organizationId().equals(organization)
                || !binding.target().organizationId().equals(organization)
                || !binding.target().teamId().equals(team)
                || binding.owner().type() != ProviderOwnerType.TEAM
                || binding.owner().teamId().filter(team::equals).isEmpty()
                || !candidate.effectiveAccess().capabilities().includes(capabilities)) {
            throw unavailable();
        }
        Connection connection = candidate.connection().orElseThrow(
                DefaultLarkConnectionAuthorizationResolver::unavailable);
        ConnectionGrant grant = candidate.connectionGrant().orElseThrow(
                DefaultLarkConnectionAuthorizationResolver::unavailable);
        if (!LarkCollaborationCapabilities.CONNECTOR_KEY.equals(connection.connectorKey())
                || !grant.grantee().equals(binding.owner())) {
            throw unavailable();
        }
        return new LarkConnectionAuthorization(
                organization,
                team,
                binding.id(),
                binding.version(),
                connection.id(),
                connection.version(),
                grant.id(),
                grant.version(),
                new LarkTenantKey(connection.externalAccountReference()),
                candidate.effectiveAccess().capabilities()).requireCapabilities(capabilities);
    }

    private static DomainValidationException unavailable() {
        return new DomainValidationException(
                "larkConnectionAuthorization",
                "Binding, Connection or Grant is unavailable for the exact Team scope");
    }

}
