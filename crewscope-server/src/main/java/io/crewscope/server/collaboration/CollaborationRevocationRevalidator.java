package io.crewscope.server.collaboration;

import io.crewscope.domain.collaboration.TeamScope;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.security.core.Authentication;
import reactor.core.publisher.Mono;

/**
 * The A01 half of the ADR-032 revocation layers: a small membership cache on the fanout
 * emission path, revalidated asynchronously. The event-driven sweep and its 4403 close
 * stay D02's; this class only answers "may this principal's frames still flow into this
 * team right now" without a durable read per frame.
 *
 * <p>Fresh entries admit the frame immediately. A stale entry also admits the frame —
 * signals carry coordinates and versions only (ADR-032), so delivering before the probe
 * returns leaks nothing — and triggers one asynchronous probe. Probes fail closed: a
 * denied verdict never distinguishes a revoked membership from an infrastructure blip,
 * and the connection is cheap to re-establish (the contract line for operators). The
 * cache interval (default 5s) plus the heartbeat (15s) bound the ADR's worst-case
 * post-revocation window.</p>
 */
public final class CollaborationRevocationRevalidator {

  private record CacheEntry(boolean member, Instant cachedAt) {}

  private static final int CACHE_RESET_THRESHOLD = 4_096;

  private final CollaborationSubscriptionAuthorizer authorizer;
  private final Duration cacheInterval;
  private final Clock clock;
  private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
  private final Map<String, Mono<Boolean>> inFlightProbes = new ConcurrentHashMap<>();

  public CollaborationRevocationRevalidator(
      CollaborationSubscriptionAuthorizer authorizer, Duration cacheInterval, Clock clock) {
    this.authorizer = Objects.requireNonNull(authorizer, "authorizer");
    this.cacheInterval = Objects.requireNonNull(cacheInterval, "cacheInterval");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * The cached verdict for a principal-in-team: present and fresh entries carry the
   * answer; everything else (unknown, stale) returns empty and asks the caller to
   * schedule a probe.
   */
  public Optional<Boolean> cached(String principalKey, OrganizationId org, TeamId team) {
    CacheEntry entry = cache.get(cacheKey(principalKey, org, team));
    if (entry == null || isStale(entry)) {
      return Optional.empty();
    }
    return Optional.of(entry.member());
  }

  /**
   * Runs one Team-scope probe through the same rule book as subscription admission and
   * stores the verdict. Concurrent callers share one in-flight probe per key. Never
   * errors: true means member, false means denied or undeterminable (fail-closed).
   */
  public Mono<Boolean> revalidate(
      String principalKey, Authentication authentication, OrganizationId org, TeamId team) {
    String key = cacheKey(principalKey, org, team);
    return inFlightProbes.computeIfAbsent(
        key,
        ignored ->
            authorizer
                .authorize(authentication, new TeamScope(org, team))
                .map(decision -> decision == CollaborationSubscriptionAuthorizer.Decision.ALLOWED)
                .onErrorReturn(false)
                .doOnNext(member -> store(key, member))
                .doFinally(signal -> inFlightProbes.remove(key))
                .cache());
  }

  private void store(String key, boolean member) {
    if (cache.size() > CACHE_RESET_THRESHOLD) {
      // A blip-shaped full reset beats an unbounded map; the next probes repopulate.
      cache.clear();
    }
    cache.put(key, new CacheEntry(member, clock.instant()));
  }

  private boolean isStale(CacheEntry entry) {
    return Duration.between(entry.cachedAt(), clock.instant()).compareTo(cacheInterval) >= 0;
  }

  private static String cacheKey(
      String principalKey, OrganizationId organizationId, TeamId teamId) {
    return principalKey + ':' + organizationId.value() + ':' + teamId.value();
  }
}
