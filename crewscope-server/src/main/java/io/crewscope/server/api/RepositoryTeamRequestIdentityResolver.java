package io.crewscope.server.api;

import io.crewscope.application.identity.AuthenticatedAccountOrganizationResolver;
import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.identity.IdentityProviderKey;
import io.crewscope.domain.identity.LoginIdentityKey;
import io.crewscope.domain.identity.LoginIdentitySubject;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.server.security.AccountSessionSubject;
import io.crewscope.server.security.AuthenticationSubjectExtractor;
import io.crewscope.server.security.ExternalAuthenticatedSubject;
import io.crewscope.server.security.PlatformRoleAuthorities;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Resolves authenticated subjects through durable Account/Binding facts without provisioning from
 * request scope.
 */
@Component
public final class RepositoryTeamRequestIdentityResolver implements TeamRequestIdentityResolver {

  private final AuthenticatedAccountOrganizationResolver accountResolver;
  private final PrincipalRepository principalRepository;
  private final AuthenticationSubjectExtractor subjectExtractor;

  public RepositoryTeamRequestIdentityResolver(
      AuthenticatedAccountOrganizationResolver accountResolver,
      PrincipalRepository principalRepository,
      AuthenticationSubjectExtractor subjectExtractor) {
    this.accountResolver = Objects.requireNonNull(accountResolver, "accountResolver");
    this.principalRepository = Objects.requireNonNull(principalRepository, "principalRepository");
    this.subjectExtractor = Objects.requireNonNull(subjectExtractor, "subjectExtractor");
  }

  @Override
  public Mono<TeamAccessContext> resolve(
      Authentication authentication, OrganizationId organizationId, UUID correlationId) {
    Authentication trusted = Objects.requireNonNull(authentication, "authentication");
    OrganizationId requiredOrganization = Objects.requireNonNull(organizationId, "organizationId");
    Objects.requireNonNull(correlationId, "correlationId");
    return Mono.fromCallable(
            () -> {
              var subject = subjectExtractor.extract(trusted);
              OrganizationId allowedOrganization =
                  subject.organizationConstraint().orElse(requiredOrganization);
              if (!allowedOrganization.equals(requiredOrganization)) {
                throw new PolicyDeniedException("act in this Organization");
              }
              if (subject instanceof AccountSessionSubject accountSession) {
                var resolution =
                    accountResolver
                        .resolveSession(
                            accountSession.accountId(),
                            accountSession.securityVersion(),
                            requiredOrganization)
                        .orElseThrow(RepositoryTeamRequestIdentityResolver::denied);
                return new TeamAccessContext(
                    resolution.principal(),
                    PlatformRoleAuthorities.isOperator(resolution.account()));
              }

              ExternalAuthenticatedSubject external =
                  (ExternalAuthenticatedSubject) subject;
              LoginIdentityKey identityKey =
                  new LoginIdentityKey(
                      new IdentityProviderKey(external.externalIdentity().provider()),
                      new LoginIdentitySubject(external.externalIdentity().subject()));
              var accountResolution =
                  accountResolver.resolveExternal(identityKey, requiredOrganization);
              if (accountResolution.isPresent()) {
                var resolution = accountResolution.orElseThrow();
                return new TeamAccessContext(
                    resolution.principal(),
                    PlatformRoleAuthorities.isOperator(resolution.account()));
              }

              // Legacy external identities remain read-only compatibility coordinates. Platform
              // authority can only come from a current persisted Account role.
              var principal =
                  principalRepository
                      .findByExternalIdentity(
                          requiredOrganization,
                          external.externalIdentity().provider(),
                          external.externalIdentity().subject())
                      .filter(
                          candidate ->
                              isCompatibleLegacyUser(
                                  candidate, requiredOrganization, external))
                      .orElseThrow(RepositoryTeamRequestIdentityResolver::denied);
              return new TeamAccessContext(principal, false);
            })
        .subscribeOn(Schedulers.boundedElastic());
  }

  private static PolicyDeniedException denied() {
    return new PolicyDeniedException("act with this account in this Organization");
  }

  private static boolean isCompatibleLegacyUser(
      Principal principal,
      OrganizationId organizationId,
      ExternalAuthenticatedSubject subject) {
    return principal.canAct()
        && principal.type() == PrincipalType.USER
        && principal.scope().organizationId().equals(organizationId)
        && principal.scope().teamId().isEmpty()
        && principal.externalIdentity().filter(subject.externalIdentity()::equals).isPresent();
  }
}
