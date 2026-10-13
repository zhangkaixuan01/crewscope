package io.crewscope.server.api;

import io.crewscope.application.organization.OrganizationAccessContext;
import io.crewscope.domain.shared.id.OrganizationId;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import reactor.core.publisher.Mono;

/** Resolves an authenticated transport subject for one organization-level request. */
public interface OrganizationRequestIdentityResolver {

    Mono<OrganizationAccessContext> resolve(
            Authentication authentication, OrganizationId organizationId, UUID correlationId);
}
