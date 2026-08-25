package io.crewscope.application.collaboration;

import io.crewscope.domain.collaboration.LarkConnectionAuthorization;
import io.crewscope.domain.provider.ProviderBindingId;
import io.crewscope.domain.provider.ProviderCapabilities;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;

/** Resolves a current ADR-006 Lark Binding/Connection/Grant authorization. */
public interface LarkConnectionAuthorizationResolver {

    LarkConnectionAuthorization resolveCurrent(
            OrganizationId organizationId,
            TeamId teamId,
            ProviderBindingId providerBindingId,
            ProviderCapabilities requiredCapabilities);
}
