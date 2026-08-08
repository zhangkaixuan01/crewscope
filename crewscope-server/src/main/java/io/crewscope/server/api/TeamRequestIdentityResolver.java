package io.crewscope.server.api;

import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.shared.id.OrganizationId;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import reactor.core.publisher.Mono;

/** Resolves an authenticated transport subject to a durable CrewScope USER Principal. */
public interface TeamRequestIdentityResolver {

  Mono<TeamAccessContext> resolve(
      Authentication authentication, OrganizationId organizationId, UUID correlationId);
}
