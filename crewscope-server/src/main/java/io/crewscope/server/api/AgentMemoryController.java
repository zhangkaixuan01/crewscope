package io.crewscope.server.api;

import io.crewscope.application.memory.AgentMemoryClearance;
import io.crewscope.application.memory.AgentMemoryService;
import io.crewscope.application.memory.AgentMemoryView;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.agent.AgentMemoryEntry;
import io.crewscope.domain.agent.AgentMemoryPolicy;
import io.crewscope.domain.agent.AgentMemoryPolicyReference;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.workspace.AgentProfileId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Function;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * M10-I02a member-facing HTTP boundary for one Agent's assistant memory: the authenticated
 * member's own view and clear. There is no owner parameter by design — the resolved
 * principal is the owner, so no cross-member read path exists. The clear answers 200 with a
 * synchronous receipt (the I01c cancel precedent: no Idempotency-Key, structurally
 * idempotent — repeating it advances the generation again and reports zero entries).
 */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/teams/{teamId}/agent-profiles/{profileId}")
public final class AgentMemoryController {

    /** The only degradation the view can carry today. */
    private static final String DEGRADED_POLICY_UNAVAILABLE = "POLICY_UNAVAILABLE";

    private final AgentMemoryService service;
    private final TeamRequestIdentityResolver identityResolver;

    public AgentMemoryController(
            AgentMemoryService service, TeamRequestIdentityResolver identityResolver) {
        this.service = service;
        this.identityResolver = identityResolver;
    }

    @GetMapping("/memory")
    public Mono<ResponseEntity<AgentMemoryViewResponse>> view(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String profileId,
            Authentication authentication,
            ServerWebExchange exchange) {
        OrganizationId organization = organizationId(organizationId);
        TeamId team = teamId(teamId);
        AgentProfileId profile = profileId(profileId);
        return query(
                        authentication,
                        organization,
                        exchange,
                        access -> service.view(access, organization, team, profile))
                .map(value -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .body(AgentMemoryViewResponse.from(value)));
    }

    @DeleteMapping("/memory")
    public Mono<ResponseEntity<AgentMemoryClearResponse>> clear(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String profileId,
            Authentication authentication,
            ServerWebExchange exchange) {
        OrganizationId organization = organizationId(organizationId);
        TeamId team = teamId(teamId);
        AgentProfileId profile = profileId(profileId);
        return query(
                        authentication,
                        organization,
                        exchange,
                        access -> service.clear(access, organization, team, profile))
                .map(value -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .body(AgentMemoryClearResponse.from(value)));
    }

    // ---------------------------------------------------------------- internals

    private <T> Mono<T> query(
            Authentication authentication,
            OrganizationId organizationId,
            ServerWebExchange exchange,
            Function<TeamAccessContext, T> action) {
        return identityResolver
                .resolve(authentication, organizationId, ApiCorrelationIds.resolve(exchange))
                .flatMap(access -> blocking(() -> action.apply(access)));
    }

    private static <T> Mono<T> blocking(Callable<T> action) {
        return Mono.fromCallable(action).subscribeOn(Schedulers.boundedElastic());
    }

    private static OrganizationId organizationId(String value) {
        try {
            return OrganizationId.from(value);
        } catch (RuntimeException failure) {
            throw invalidField("organizationId");
        }
    }

    private static TeamId teamId(String value) {
        try {
            return TeamId.from(value);
        } catch (RuntimeException failure) {
            throw invalidField("teamId");
        }
    }

    private static AgentProfileId profileId(String value) {
        try {
            return AgentProfileId.from(value);
        } catch (RuntimeException failure) {
            throw invalidField("profileId");
        }
    }

    private static ApiRequestException invalidField(String field) {
        return new ApiRequestException(
                HttpStatus.BAD_REQUEST,
                "invalid_request",
                "Request contains an invalid agent memory field",
                Map.of("field", field));
    }

    /**
     * The three view states (S01 §3.6): no {@code policyReference} = memory not configured
     * on the current Agent configuration; both present = healthy; reference present but no
     * {@code policy} = the referenced version is unresolvable and the view degrades.
     */
    public record AgentMemoryViewResponse(
            PolicyReferenceResponse policyReference,
            PolicyResponse policy,
            String degraded,
            long clearanceGeneration,
            List<EntryResponse> entries,
            int entryCount) {
        static AgentMemoryViewResponse from(AgentMemoryView view) {
            return new AgentMemoryViewResponse(
                    view.policyReference().map(PolicyReferenceResponse::from).orElse(null),
                    view.policy().map(PolicyResponse::from).orElse(null),
                    view.policyUnavailable() ? DEGRADED_POLICY_UNAVAILABLE : null,
                    view.clearanceGeneration(),
                    view.entries().stream().map(EntryResponse::from).toList(),
                    view.entries().size());
        }
    }

    public record PolicyReferenceResponse(String policyId, long version) {
        static PolicyReferenceResponse from(AgentMemoryPolicyReference reference) {
            return new PolicyReferenceResponse(
                    reference.policyId().toString(), reference.version());
        }
    }

    public record PolicyResponse(
            String policyId,
            long version,
            int ttlDays,
            int maxEntriesPerOwner,
            int valueMaxBytes) {
        static PolicyResponse from(AgentMemoryPolicy policy) {
            return new PolicyResponse(
                    policy.policyId().toString(),
                    policy.version(),
                    policy.ttlDays(),
                    policy.maxEntriesPerOwner(),
                    policy.valueMaxBytes());
        }
    }

    public record EntryResponse(
            String memoryKey,
            String value,
            long version,
            String expiresAt,
            String createdAt,
            String updatedAt,
            String createdBy,
            String updatedBy) {
        static EntryResponse from(AgentMemoryEntry entry) {
            return new EntryResponse(
                    entry.memoryKey().value(),
                    entry.value(),
                    entry.version(),
                    entry.expiresAt().toString(),
                    entry.audit().createdAt().toString(),
                    entry.audit().updatedAt().toString(),
                    entry.audit().createdBy().map(id -> id.value().toString()).orElse(null),
                    entry.audit().updatedBy().map(id -> id.value().toString()).orElse(null));
        }
    }

    /** Synchronous clear receipt: how many entries went and the new clearance generation. */
    public record AgentMemoryClearResponse(long clearedCount, long clearanceGeneration) {
        static AgentMemoryClearResponse from(AgentMemoryClearance clearance) {
            return new AgentMemoryClearResponse(
                    clearance.clearedCount(), clearance.clearanceGeneration());
        }
    }
}
