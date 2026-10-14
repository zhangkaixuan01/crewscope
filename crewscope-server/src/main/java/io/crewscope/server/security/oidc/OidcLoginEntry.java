package io.crewscope.server.security.oidc;

import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import reactor.core.publisher.Mono;

/**
 * Projects the browser SSO entry point for the Session response without coupling controllers to
 * the client-registration registry. The path stays framework-owned ({@code
 * /oauth2/authorization/{registrationId}}) so the redirect flow keeps its single login entry.
 */
public final class OidcLoginEntry {

  private final ObjectProvider<ReactiveClientRegistrationRepository> repositories;

  public OidcLoginEntry(ObjectProvider<ReactiveClientRegistrationRepository> repositories) {
    this.repositories = Objects.requireNonNull(repositories, "repositories");
  }

  /** Authorization path of the first configured registration; empty when SSO is not configured. */
  public Mono<String> authorizationPath() {
    ReactiveClientRegistrationRepository repository = repositories.getIfAvailable();
    if (!(repository instanceof Iterable<?> registrations)) {
      return Mono.empty();
    }
    for (Object candidate : registrations) {
      if (candidate instanceof ClientRegistration registration) {
        return Mono.just("/oauth2/authorization/" + registration.getRegistrationId());
      }
    }
    return Mono.empty();
  }
}
