package io.crewscope.server.collaboration;

import io.crewscope.domain.collaboration.CollaborationResourceScope;
import io.crewscope.domain.identity.Principal;
import org.springframework.security.core.Authentication;
import reactor.core.publisher.Mono;

/**
 * Per-subscription admission (M11-I01b): every subscribe frame re-intersects the transport
 * identity with the durable authorization facts. A Mono port living in server (not the
 * application module) follows the {@code TeamRequestIdentityResolver} convention — the product
 * implementation bridges blocking JPA adjudication onto a scheduler; test fixtures provide
 * fakes. The A01 revocation revalidation layer ({@link CollaborationRevocationRevalidator})
 * probes this same port asynchronously for live connections — there is no second rulebook —
 * while the event-driven 4403 on membership lifecycle events is the D02 sweep implemented on
 * top of the resolved {@link Admission} identity.
 */
public interface CollaborationSubscriptionAuthorizer {

  /** The only two outcomes; no reason codes, because denial must stay indistinguishable. */
  enum Decision {
    ALLOWED,
    DENIED
  }

  /**
   * The admission verdict plus, when allowed, the durable {@link Principal} the verdict was
   * made for — the only bridge between a transport identity and the member coordinates the
   * D02 revocation events name. Denied admissions carry {@code null}; callers must not
   * dereference the principal of a denied admission.
   */
  record Admission(Decision decision, Principal principal) {

    public boolean allowed() {
      return decision == Decision.ALLOWED;
    }
  }

  /**
   * Admits one scope for one authenticated transport subject and reports the resolved
   * identity. Never errors: every failure (unknown aggregate, policy denial,
   * infrastructure) resolves to a denied {@link Admission} without a principal
   * (fail-closed, the ADR-038 precedent), so a resource's absence stays indistinguishable
   * from a reader's lack of permission.
   */
  Mono<Admission> admit(Authentication authentication, CollaborationResourceScope scope);

  /**
   * The verdict-only projection; the A01 revalidation probe and other identity-agnostic
   * callers keep using this shape.
   */
  default Mono<Decision> authorize(Authentication authentication, CollaborationResourceScope scope) {
    return admit(authentication, scope).map(Admission::decision);
  }
}
