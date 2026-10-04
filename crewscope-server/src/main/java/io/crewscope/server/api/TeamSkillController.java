package io.crewscope.server.api;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.application.skill.CreateTeamSkillCommand;
import io.crewscope.application.skill.DistillTeamSkillCommand;
import io.crewscope.application.skill.SkillDistillationService;
import io.crewscope.application.skill.TeamSkillCommandService;
import io.crewscope.application.skill.TeamSkillFilter;
import io.crewscope.application.skill.TeamSkillPage;
import io.crewscope.application.skill.TeamSkillPageRequest;
import io.crewscope.application.skill.TeamSkillVersionPage;
import io.crewscope.application.skill.TeamSkillVersionPageRequest;
import io.crewscope.application.skill.UpdateTeamSkillDraftCommand;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.skill.TeamSkill;
import io.crewscope.domain.skill.TeamSkillId;
import io.crewscope.domain.skill.TeamSkillKey;
import io.crewscope.domain.skill.TeamSkillOrigin;
import io.crewscope.domain.skill.TeamSkillRevision;
import io.crewscope.domain.skill.TeamSkillStatus;
import io.crewscope.domain.skill.TeamSkillVersion;
import io.crewscope.domain.task.TaskExecutionId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Function;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * A03a HTTP boundary for the Team Skill catalog: lifecycle commands gated on
 * SKILL_MANAGE and the {@code crewscope.skill.enabled} write switch, plus member-wide
 * reads of heads and immutable versions. There is no delete: a skill is disabled and
 * its history stays as evidence; a rollback activates historical content as a new
 * revision (ADR-031 §4).
 */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/teams/{teamId}/skills")
public final class TeamSkillController {

    private final TeamSkillCommandService service;
    private final SkillDistillationService distillationService;
    private final TeamRequestIdentityResolver identityResolver;

    public TeamSkillController(
            TeamSkillCommandService service,
            SkillDistillationService distillationService,
            TeamRequestIdentityResolver identityResolver) {
        this.service = service;
        this.distillationService = distillationService;
        this.identityResolver = identityResolver;
    }

    @PostMapping
    public Mono<ResponseEntity<CommandReceiptResponse>> create(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
            @Valid @RequestBody CreateSkillBody request,
            Authentication authentication,
            ServerWebExchange exchange) {
        OrganizationId organization = organizationId(organizationId);
        TeamId team = teamId(teamId);
        IdempotencyKey idempotencyKey = ApiHeaders.requireIdempotencyKey(key);
        CreateTeamSkillCommand command = new CreateTeamSkillCommand(
                skillKey(request.skillKey()),
                request.content());
        return command(
                authentication,
                organization,
                idempotencyKey,
                exchange,
                context -> service.create(context, team, command));
    }

    /**
     * A03b member-initiated skill distillation: only the Task's creator (or a platform
     * administrator) may run it, and the choice is theirs — not a SKILL_MANAGE grant.
     * Synchronously acknowledged with 202 once the DRAFT skill, its origin and the
     * DISTILLATION usage facts have committed.
     */
    @PostMapping("/distillations")
    public Mono<ResponseEntity<DistillationAcceptedResponse>> distill(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
            @Valid @RequestBody DistillSkillBody request,
            Authentication authentication,
            ServerWebExchange exchange) {
        OrganizationId organization = organizationId(organizationId);
        TeamId team = teamId(teamId);
        IdempotencyKey idempotencyKey = ApiHeaders.requireIdempotencyKey(key);
        DistillTeamSkillCommand command = new DistillTeamSkillCommand(
                distillExecutionId(request.taskExecutionId()),
                skillKey(request.skillKey()));
        UUID correlationId = ApiCorrelationIds.resolve(exchange);
        return identityResolver
                .resolve(authentication, organization, correlationId)
                .flatMap(access -> Mono
                        .fromCompletionStage(() -> distillationService.distill(
                                new TeamCommandContext(access, idempotencyKey, correlationId,
                                        Optional.empty()),
                                team, command))
                        .subscribeOn(Schedulers.boundedElastic()))
                .map(execution -> {
                    ResponseEntity.BodyBuilder response =
                            ResponseEntity.accepted().cacheControl(CacheControl.noStore());
                    if (execution.replayed()) {
                        response.header(ApiHeaders.IDEMPOTENCY_REPLAYED, "true");
                    }
                    return response.body(DistillationAcceptedResponse.accepted(
                            execution, command.skillKey().value()));
                });
    }

    @GetMapping
    public Mono<ResponseEntity<SkillListResponse>> list(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String after,
            @RequestParam(required = false) Integer limit,
            Authentication authentication,
            ServerWebExchange exchange) {
        OrganizationId organization = organizationId(organizationId);
        TeamId team = teamId(teamId);
        TeamSkillFilter filter = filter(status);
        TeamSkillPageRequest pageRequest =
                new TeamSkillPageRequest(afterSkillKey(after), ApiPagination.limit(limit));
        return query(
                        authentication,
                        organization,
                        exchange,
                        access -> service.teamListing(access, organization, team, filter, pageRequest))
                .map(page -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .body(SkillListResponse.from(page)));
    }

    @GetMapping("/{skillId}")
    public Mono<ResponseEntity<SkillResponse>> get(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String skillId,
            Authentication authentication,
            ServerWebExchange exchange) {
        OrganizationId organization = organizationId(organizationId);
        TeamId team = teamId(teamId);
        TeamSkillId skill = skillId(skillId);
        return query(
                        authentication,
                        organization,
                        exchange,
                        access -> service.skill(access, organization, team, skill))
                .map(value -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .eTag(ApiHeaders.versionEtag(value.version()))
                        .body(SkillResponse.from(value)));
    }

    @PatchMapping("/{skillId}")
    public Mono<ResponseEntity<CommandReceiptResponse>> updateDraft(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String skillId,
            @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
            @RequestHeader(name = ApiHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody UpdateDraftBody request,
            Authentication authentication,
            ServerWebExchange exchange) {
        OrganizationId organization = organizationId(organizationId);
        TeamId team = teamId(teamId);
        TeamSkillId skill = skillId(skillId);
        long expectedVersion = ApiHeaders.requireIfMatch(ifMatch);
        IdempotencyKey idempotencyKey = ApiHeaders.requireIdempotencyKey(key);
        UpdateTeamSkillDraftCommand command = new UpdateTeamSkillDraftCommand(request.content());
        return command(
                authentication,
                organization,
                idempotencyKey,
                exchange,
                context -> service.updateDraft(
                        context, team, skill, expectedVersion, command));
    }

    @PostMapping("/{skillId}/publish")
    public Mono<ResponseEntity<CommandReceiptResponse>> publish(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String skillId,
            @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
            @RequestHeader(name = ApiHeaders.IF_MATCH, required = false) String ifMatch,
            Authentication authentication,
            ServerWebExchange exchange) {
        return mutatingCommand(
                organizationId,
                teamId,
                skillId,
                key,
                ifMatch,
                authentication,
                exchange,
                (context, team, skill, expectedVersion) ->
                        service.publish(context, team, skill, expectedVersion));
    }

    @PostMapping("/{skillId}/disable")
    public Mono<ResponseEntity<CommandReceiptResponse>> disable(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String skillId,
            @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
            @RequestHeader(name = ApiHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody(required = false) DisableBody request,
            Authentication authentication,
            ServerWebExchange exchange) {
        OrganizationId organization = organizationId(organizationId);
        TeamId team = teamId(teamId);
        TeamSkillId skill = skillId(skillId);
        long expectedVersion = ApiHeaders.requireIfMatch(ifMatch);
        IdempotencyKey idempotencyKey = ApiHeaders.requireIdempotencyKey(key);
        String reason = request == null ? null : request.reason();
        return command(
                authentication,
                organization,
                idempotencyKey,
                exchange,
                context -> service.disable(context, team, skill, expectedVersion, reason));
    }

    @PostMapping("/{skillId}/rollback")
    public Mono<ResponseEntity<CommandReceiptResponse>> rollback(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String skillId,
            @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
            @RequestHeader(name = ApiHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody RollbackBody request,
            Authentication authentication,
            ServerWebExchange exchange) {
        OrganizationId organization = organizationId(organizationId);
        TeamId team = teamId(teamId);
        TeamSkillId skill = skillId(skillId);
        long expectedVersion = ApiHeaders.requireIfMatch(ifMatch);
        IdempotencyKey idempotencyKey = ApiHeaders.requireIdempotencyKey(key);
        long toRevision = requireToRevision(request.toRevision());
        return command(
                authentication,
                organization,
                idempotencyKey,
                exchange,
                context -> service.rollback(context, team, skill, expectedVersion, toRevision));
    }

    @GetMapping("/{skillId}/versions")
    public Mono<ResponseEntity<VersionListResponse>> versions(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String skillId,
            @RequestParam(required = false) Long after,
            @RequestParam(required = false) Integer limit,
            Authentication authentication,
            ServerWebExchange exchange) {
        OrganizationId organization = organizationId(organizationId);
        TeamId team = teamId(teamId);
        TeamSkillId skill = skillId(skillId);
        TeamSkillVersionPageRequest pageRequest = new TeamSkillVersionPageRequest(
                afterRevision(after), ApiPagination.limit(limit));
        return query(
                        authentication,
                        organization,
                        exchange,
                        access -> service.versionHistory(
                                access, organization, team, skill, pageRequest))
                .map(page -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .body(VersionListResponse.from(page)));
    }

    @GetMapping("/{skillId}/versions/{revision}")
    public Mono<ResponseEntity<VersionResponse>> version(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String skillId,
            @PathVariable String revision,
            Authentication authentication,
            ServerWebExchange exchange) {
        OrganizationId organization = organizationId(organizationId);
        TeamId team = teamId(teamId);
        TeamSkillId skill = skillId(skillId);
        TeamSkillRevision parsed = revision(revision);
        return query(
                        authentication,
                        organization,
                        exchange,
                        access -> service.version(access, organization, team, skill, parsed))
                .map(value -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .eTag(contentHashEtag(value))
                        .body(VersionResponse.from(value)));
    }

    @GetMapping("/{skillId}/effective-version")
    public Mono<ResponseEntity<VersionResponse>> effectiveVersion(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String skillId,
            Authentication authentication,
            ServerWebExchange exchange) {
        OrganizationId organization = organizationId(organizationId);
        TeamId team = teamId(teamId);
        TeamSkillId skill = skillId(skillId);
        return query(
                        authentication,
                        organization,
                        exchange,
                        access -> service.effectiveVersion(access, organization, team, skill))
                .map(value -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .eTag(contentHashEtag(value))
                        .body(VersionResponse.from(value)));
    }

    // ---------------------------------------------------------------- internals

    private interface MutatingAction {
        CommandExecution<TeamSkill> apply(
                TeamCommandContext context,
                TeamId team,
                TeamSkillId skill,
                long expectedVersion);
    }

    private Mono<ResponseEntity<CommandReceiptResponse>> mutatingCommand(
            String organizationValue,
            String teamValue,
            String skillValue,
            String key,
            String ifMatch,
            Authentication authentication,
            ServerWebExchange exchange,
            MutatingAction action) {
        OrganizationId organization = organizationId(organizationValue);
        TeamId team = teamId(teamValue);
        TeamSkillId skill = skillId(skillValue);
        long expectedVersion = ApiHeaders.requireIfMatch(ifMatch);
        IdempotencyKey idempotencyKey = ApiHeaders.requireIdempotencyKey(key);
        return command(
                authentication,
                organization,
                idempotencyKey,
                exchange,
                context -> action.apply(context, team, skill, expectedVersion));
    }

    private <T> Mono<ResponseEntity<CommandReceiptResponse>> command(
            Authentication authentication,
            OrganizationId organizationId,
            IdempotencyKey idempotencyKey,
            ServerWebExchange exchange,
            Function<TeamCommandContext, CommandExecution<T>> action) {
        UUID correlationId = ApiCorrelationIds.resolve(exchange);
        return identityResolver
                .resolve(authentication, organizationId, correlationId)
                .flatMap(access -> blocking(() -> action.apply(new TeamCommandContext(
                        access, idempotencyKey, correlationId, Optional.empty()))))
                .map(CommandReceiptResponse::accepted);
    }

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

    private static TeamSkillFilter filter(String status) {
        if (status == null) {
            return TeamSkillFilter.all();
        }
        return new TeamSkillFilter(Set.of(parseEnum(status, TeamSkillStatus::valueOf, "status")));
    }

    private static Optional<TeamSkillKey> afterSkillKey(String after) {
        return Optional.ofNullable(after).map(value -> {
            try {
                return TeamSkillKey.parse(value);
            } catch (RuntimeException failure) {
                throw invalidField("after");
            }
        });
    }

    private static Optional<TeamSkillRevision> afterRevision(Long after) {
        if (after == null) {
            return Optional.empty();
        }
        if (after < 1) {
            throw invalidField("after");
        }
        return Optional.of(new TeamSkillRevision(after));
    }

    private static TeamSkillRevision revision(String value) {
        try {
            TeamSkillRevision parsed = new TeamSkillRevision(Long.parseLong(value));
            if (parsed.value() < 1) {
                throw new IllegalArgumentException("revision must be positive");
            }
            return parsed;
        } catch (RuntimeException failure) {
            throw invalidField("revision");
        }
    }

    private static TeamSkillKey skillKey(String value) {
        try {
            return TeamSkillKey.parse(value);
        } catch (RuntimeException failure) {
            throw invalidField("skillKey");
        }
    }

    private static TaskExecutionId distillExecutionId(String value) {
        try {
            return TaskExecutionId.from(value);
        } catch (RuntimeException failure) {
            throw invalidField("taskExecutionId");
        }
    }

    private static long requireToRevision(Long value) {
        if (value == null || value < 1) {
            throw invalidField("toRevision");
        }
        return value;
    }

    private static <E extends Enum<E>> E parseEnum(
            String value, Function<String, E> parser, String field) {
        try {
            return parser.apply(value);
        } catch (RuntimeException failure) {
            throw invalidField(field);
        }
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

    private static TeamSkillId skillId(String value) {
        try {
            return TeamSkillId.from(value);
        } catch (RuntimeException failure) {
            throw invalidField("skillId");
        }
    }

    private static String contentHashEtag(TeamSkillVersion value) {
        return "\"" + value.contentHash().value() + "\"";
    }

    private static ApiRequestException invalidField(String field) {
        return new ApiRequestException(
                HttpStatus.BAD_REQUEST,
                "invalid_request",
                "Request contains an invalid team skill field",
                Map.of("field", field));
    }

    public record CreateSkillBody(
            @NotBlank String skillKey,
            @NotBlank @Size(max = 65536) String content) {}

    public record DistillSkillBody(
            @NotNull String taskExecutionId,
            @NotBlank String skillKey) {}

    /**
     * 202 receipt for first execution and replay alike; a replay never re-exposes the
     * skill id, but the caller-chosen immutable {@code skillKey} always identifies it.
     * The status is always DRAFT — publication stays a separate SKILL_MANAGE command.
     */
    public record DistillationAcceptedResponse(
            UUID commandId,
            UUID domainEventId,
            long committedVersion,
            UUID correlationId,
            String skillId,
            String skillKey,
            String status,
            OriginResponse origin) {

        static DistillationAcceptedResponse accepted(
                CommandExecution<TeamSkill> execution, String skillKey) {
            TeamSkill skill = execution.result().orElse(null);
            return new DistillationAcceptedResponse(
                    execution.receipt().commandId(),
                    execution.receipt().domainEventId(),
                    execution.receipt().committedVersion(),
                    execution.receipt().correlationId(),
                    skill == null ? null : skill.id().value().toString(),
                    skillKey,
                    skill == null ? null : skill.status().name(),
                    skill == null ? null : OriginResponse.from(skill));
        }
    }

    public record UpdateDraftBody(
            @NotBlank @Size(max = 65536) String content) {}

    /** The reason is optional: disabling without one is a silent retirement. */
    public record DisableBody(@Size(max = 200) String reason) {}

    public record RollbackBody(@NotNull @Positive Long toRevision) {}

    public record SkillListResponse(List<SkillResponse> items, String nextAfter) {
        static SkillListResponse from(TeamSkillPage page) {
            return new SkillListResponse(
                    page.items().stream().map(SkillResponse::from).toList(),
                    page.nextSkillKey().map(key -> key.value()).orElse(null));
        }
    }

    /** Management head view; the head version is the strong ETag source. */
    public record SkillResponse(
            String id,
            String skillKey,
            String status,
            Long effectiveRevision,
            long latestRevision,
            DraftResponse draft,
            String disableReason,
            long version,
            String createdAt,
            String updatedAt,
            String createdBy,
            String updatedBy,
            OriginResponse origin) {
        static SkillResponse from(TeamSkill value) {
            return new SkillResponse(
                    value.id().value().toString(),
                    value.skillKey().value(),
                    value.status().name(),
                    value.effectiveRevision().map(revision -> revision.value()).orElse(null),
                    value.latestRevision(),
                    value.draft().map(DraftResponse::from).orElse(null),
                    value.disableReason().orElse(null),
                    value.version(),
                    value.audit().createdAt().toString(),
                    value.audit().updatedAt().toString(),
                    value.audit().createdBy().map(id -> id.value().toString()).orElse(null),
                    value.audit().updatedBy().map(id -> id.value().toString()).orElse(null),
                    OriginResponse.from(value));
        }
    }

    /** A03b distillation attribution slot; null for manually authored entries. */
    public record OriginResponse(String taskExecutionId, int attempt) {

        static OriginResponse from(TeamSkill value) {
            return value.origin()
                    .map(TeamSkillController::originResponse)
                    .orElse(null);
        }
    }

    private static OriginResponse originResponse(TeamSkillOrigin origin) {
        return new OriginResponse(origin.taskExecutionId().toString(), origin.attempt());
    }

    /** The working SKILL.md document exactly as the runtime repository will serve it. */
    public record DraftResponse(String name, String description, String content) {
        static DraftResponse from(io.crewscope.domain.skill.TeamSkillDraft value) {
            return new DraftResponse(value.name(), value.description(), value.content());
        }
    }

    public record VersionListResponse(List<VersionResponse> items, Long nextAfter) {
        static VersionListResponse from(TeamSkillVersionPage page) {
            return new VersionListResponse(
                    page.items().stream().map(VersionResponse::from).toList(),
                    page.nextRevision().map(revision -> revision.value()).orElse(null));
        }
    }

    /** Immutable published revision; the content hash is the strong ETag source. */
    public record VersionResponse(
            String skillId,
            long revision,
            Long previousRevision,
            String content,
            String contentHash,
            String createdAt,
            String createdBy) {
        static VersionResponse from(TeamSkillVersion value) {
            return new VersionResponse(
                    value.skillId().value().toString(),
                    value.revision().value(),
                    value.previousRevision().map(revision -> revision.value()).orElse(null),
                    value.content(),
                    value.contentHash().value(),
                    value.audit().createdAt().toString(),
                    value.audit().createdBy().map(id -> id.value().toString()).orElse(null));
        }
    }
}
