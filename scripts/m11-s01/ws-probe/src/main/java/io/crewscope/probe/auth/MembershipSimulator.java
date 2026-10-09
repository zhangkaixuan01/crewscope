package io.crewscope.probe.auth;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * In-memory stand-in for the product's membership facts
 * (team_member row + authorizationVersion), used by the revalidation layer
 * of ADR-038 section 3.1. The probe never writes member state anywhere
 * durable — that boundary is the point of the spike: revocation authority
 * lives outside the channel, the channel only re-reads it.
 *
 * <p>Calls are cached for {@code revalidationCache} (5s), mirroring the SSE
 * idle-probe bound: layer-2 revocations therefore take effect on the first
 * frame emitted after the cache expires, not instantly — the probe measures
 * exactly that window.</p>
 */
@Component
public class MembershipSimulator {

    private record CachedVerdict(boolean member, Instant verifiedAt) {}

    private final Map<String, Set<String>> teamMembers = new ConcurrentHashMap<>();
    private final Map<String, CachedVerdict> verdictCache = new ConcurrentHashMap<>();
    private final Map<String, Long> revokedCount = new ConcurrentHashMap<>();

    private final java.time.Duration cacheTtl;

    public MembershipSimulator(io.crewscope.probe.config.ProbeProperties properties) {
        this.cacheTtl = properties.revalidationCache();
    }

    /** Registers a principal as an active member of a team (login path). */
    public void activate(String teamId, String principalId) {
        teamMembers.computeIfAbsent(teamId, key -> ConcurrentHashMap.newKeySet()).add(principalId);
    }

    /**
     * Revalidation-layer check with the frozen cache bound. Returns true only
     * when the cached-or-fresh verdict says the principal is still an active
     * member of the team.
     */
    public boolean isMember(String teamId, String principalId) {
        String key = teamId + "/" + principalId;
        CachedVerdict cached = verdictCache.get(key);
        if (cached != null && cached.verifiedAt().isAfter(Instant.now().minus(cacheTtl))) {
            return cached.member();
        }
        boolean fresh = teamMembers.getOrDefault(teamId, Set.of()).contains(principalId);
        verdictCache.put(key, new CachedVerdict(fresh, Instant.now()));
        if (!fresh) {
            revokedCount.merge(key, 1L, Long::sum);
        }
        return fresh;
    }

    /** Revocation (suspend/remove/leave all collapse to "no longer a member"). */
    public void revoke(String teamId, String principalId) {
        teamMembers.getOrDefault(teamId, Set.of()).remove(principalId);
        verdictCache.remove(teamId + "/" + principalId);
    }

    /** Number of times a revalidation observed this principal as revoked. */
    public long revocationObservations(String teamId, String principalId) {
        return revokedCount.getOrDefault(teamId + "/" + principalId, 0L);
    }
}
