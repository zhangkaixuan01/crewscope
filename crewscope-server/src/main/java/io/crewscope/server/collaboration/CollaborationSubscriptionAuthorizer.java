package io.crewscope.server.collaboration;

import io.crewscope.domain.collaboration.CollaborationResourceScope;
import org.springframework.security.core.Authentication;
import reactor.core.publisher.Mono;

/**
 * Per-subscription admission (M11-I01b): every subscribe frame re-intersects the transport
 * identity with the durable authorization facts. A Mono port living in server (not the
 * application module) follows the {@code TeamRequestIdentityResolver} convention — the product
 * implementation bridges blocking JPA adjudication onto a scheduler; test fixtures provide
 * fakes. Outbound-frame revalidation and the 4403 close belong to D02, not this port.
 */
public interface CollaborationSubscriptionAuthorizer {

  /** The only two outcomes; no reason codes, because denial must stay indistinguishable. */
  enum Decision {
    ALLOWED,
    DENIED
  }

  /**
   * Authorizes one scope for one authenticated transport subject. Never errors: every failure
   * (unknown aggregate, policy denial, infrastructure) resolves to {@link Decision#DENIED}
   * (fail-closed, the ADR-038 precedent), so a resource's absence stays indistinguishable
   * from a reader's lack of permission.
   */
  Mono<Decision> authorize(Authentication authentication, CollaborationResourceScope scope);
}
