package io.crewscope.server.api;

import io.crewscope.application.identity.IdentityMappingRequest;
import io.crewscope.application.identity.IdentityMappingService;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.server.security.AuthenticationSubjectExtractor;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Resolves Basic/OIDC subjects through durable Principal mappings without trusting actor headers.
 */
@Component
public final class RepositoryTeamRequestIdentityResolver implements TeamRequestIdentityResolver {

  private final IdentityMappingService identityMappingService;
  private final AuthenticationSubjectExtractor subjectExtractor;

  public RepositoryTeamRequestIdentityResolver(
      IdentityMappingService identityMappingService,
      AuthenticationSubjectExtractor subjectExtractor) {
    this.identityMappingService =
        Objects.requireNonNull(identityMappingService, "identityMappingService");
    this.subjectExtractor = Objects.requireNonNull(subjectExtractor, "subjectExtractor");
  }

  @Override
  public Mono<TeamAccessContext> resolve(
      Authentication authentication, OrganizationId organizationId, UUID correlationId) {
    Authentication trusted = Objects.requireNonNull(authentication, "authentication");
    OrganizationId requiredOrganization = Objects.requireNonNull(organizationId, "organizationId");
    UUID requiredCorrelation = Objects.requireNonNull(correlationId, "correlationId");
    boolean administrator =
        trusted.getAuthorities().stream()
            .anyMatch(authority -> authority.getAuthority().equals("ROLE_ADMIN"));
    return Mono.fromCallable(
            () -> {
              var subject = subjectExtractor.extract(trusted);
              OrganizationId allowedOrganization =
                  subject.organizationConstraint().orElse(requiredOrganization);
              if (!allowedOrganization.equals(requiredOrganization)) {
                throw new PolicyDeniedException("act in this Organization");
              }
              var mapped =
                  identityMappingService.map(
                      new IdentityMappingRequest(
                          requiredOrganization,
                          subject.externalIdentity(),
                          subject.displayName(),
                          requiredCorrelation));
              return new TeamAccessContext(mapped.principal(), administrator);
            })
        .subscribeOn(Schedulers.boundedElastic());
  }
}
