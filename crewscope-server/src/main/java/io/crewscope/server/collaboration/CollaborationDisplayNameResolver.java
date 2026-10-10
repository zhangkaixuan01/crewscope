package io.crewscope.server.collaboration;

import org.springframework.security.core.Authentication;
import reactor.core.publisher.Mono;

/**
 * Resolves the display name carried by presence and typing signals (M11-A01). Purely a
 * presentation label: implementations must never surface a failure as an error — an
 * unresolvable name falls back to a placeholder, because a missing label degrades
 * presentation and nothing else. ADR-024 leaves the account-name-versus-member-name
 * choice open; this port is the single seam where a later slice may swap the source.
 */
public interface CollaborationDisplayNameResolver {

  /** Emits the display name for the authenticated principal; never an error signal. */
  Mono<String> resolve(Authentication authentication);
}
