package io.crewscope.server.api;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.application.organization.OrganizationAccessContext;
import io.crewscope.application.organization.OrganizationCommandContext;
import io.crewscope.application.organization.governance.OrganizationAccountDeletionApplicationService;
import io.crewscope.domain.identity.UserAccountId;
import io.crewscope.domain.organization.governance.AccountDeletionItem;
import io.crewscope.domain.organization.governance.AccountDeletionJob;
import io.crewscope.domain.organization.governance.AccountDeletionJobId;
import io.crewscope.domain.shared.id.OrganizationId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Function;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
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
 * HTTP boundary of the account deletion workflow (M12-A01). The two-step confirmation is the
 * contract: create freezes the checklist behind hard preconditions and waits in
 * PENDING_CONFIRMATION; only confirm — with the target's username echo and the observed job
 * version — unlocks processing. Cancel keeps settled steps; a re-run converges idempotently.
 */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}")
public final class OrganizationDeletionController {

    private static final int REASON_MAX_LENGTH = 500;

    private final OrganizationAccountDeletionApplicationService service;
    private final OrganizationRequestIdentityResolver identityResolver;

    public OrganizationDeletionController(
            OrganizationAccountDeletionApplicationService service,
            OrganizationRequestIdentityResolver identityResolver) {
        this.service = service;
        this.identityResolver = identityResolver;
    }

    /** Read-side precondition report; steers the caller through handover before create. */
    @GetMapping("/deletion-precheck")
    public Mono<ResponseEntity<DeletionPrecheckResponse>> precheck(
            @PathVariable String organizationId,
            @RequestParam String accountId,
            Authentication authentication,
            ServerWebExchange exchange) {
        OrganizationId organization = organizationId(organizationId);
        UserAccountId account = accountId(accountId);
        return query(
                        authentication,
                        organization,
                        exchange,
                        (correlationId, access) ->
                                service.precheck(access, organization, account))
                .map(
                        view ->
                                ResponseEntity.ok()
                                        .cacheControl(CacheControl.noStore())
                                        .body(DeletionPrecheckResponse.from(view)));
    }

    @PostMapping("/account-deletions")
    public Mono<ResponseEntity<Object>> create(
            @PathVariable String organizationId,
            @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
            @Valid @RequestBody CreateDeletionRequest request,
            Authentication authentication,
            ServerWebExchange exchange) {
        OrganizationId organization = organizationId(organizationId);
        UserAccountId account = accountId(request.accountId());
        IdempotencyKey idempotencyKey = ApiHeaders.requireIdempotencyKey(key);
        return command(
                        authentication,
                        organization,
                        idempotencyKey,
                        exchange,
                        context -> service.create(context, organization, account, request.reason()))
                .map(OrganizationDeletionController::acceptedJob);
    }

    /** The second, deliberate step: username echo plus the observed job version. */
    @PostMapping("/account-deletions/{jobId}/confirm")
    public Mono<ResponseEntity<DeletionJobResponse>> confirm(
            @PathVariable String organizationId,
            @PathVariable String jobId,
            @RequestHeader(name = ApiHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody ConfirmDeletionRequest request,
            Authentication authentication,
            ServerWebExchange exchange) {
        OrganizationId organization = organizationId(organizationId);
        AccountDeletionJobId job = jobId(jobId);
        long expectedVersion = ApiHeaders.requireIfMatch(ifMatch);
        return query(
                        authentication,
                        organization,
                        exchange,
                        (correlationId, access) ->
                                service.confirm(
                                        access,
                                        organization,
                                        job,
                                        request.confirmationToken(),
                                        expectedVersion,
                                        correlationId))
                .map(OrganizationDeletionController::okJob);
    }

    /** Settles every PENDING step; interrupted runs resume at the first unsettled one. */
    @PostMapping("/account-deletions/{jobId}/process")
    public Mono<ResponseEntity<DeletionJobResponse>> process(
            @PathVariable String organizationId,
            @PathVariable String jobId,
            Authentication authentication,
            ServerWebExchange exchange) {
        OrganizationId organization = organizationId(organizationId);
        AccountDeletionJobId job = jobId(jobId);
        return query(
                        authentication,
                        organization,
                        exchange,
                        (correlationId, access) ->
                                service.process(access, organization, job, correlationId))
                .map(OrganizationDeletionController::okJob);
    }

    @PostMapping("/account-deletions/{jobId}/cancel")
    public Mono<ResponseEntity<DeletionJobResponse>> cancel(
            @PathVariable String organizationId,
            @PathVariable String jobId,
            Authentication authentication,
            ServerWebExchange exchange) {
        OrganizationId organization = organizationId(organizationId);
        AccountDeletionJobId job = jobId(jobId);
        return query(
                        authentication,
                        organization,
                        exchange,
                        (correlationId, access) ->
                                service.cancel(access, organization, job, correlationId))
                .map(OrganizationDeletionController::okJob);
    }

    /** The reconciliation surface: job + the thirteen-step coordination checklist. */
    @GetMapping("/account-deletions/{jobId}")
    public Mono<ResponseEntity<DeletionJobResponse>> get(
            @PathVariable String organizationId,
            @PathVariable String jobId,
            Authentication authentication,
            ServerWebExchange exchange) {
        OrganizationId organization = organizationId(organizationId);
        AccountDeletionJobId job = jobId(jobId);
        return query(
                        authentication,
                        organization,
                        exchange,
                        (correlationId, access) -> service.get(access, organization, job))
                .map(OrganizationDeletionController::okJob);
    }

    /** 202 with the queued job on first execution; the receipt alone on an idempotent replay. */
    private static ResponseEntity<Object> acceptedJob(
            CommandExecution<OrganizationAccountDeletionApplicationService.DeletionJobView>
                    execution) {
        ResponseEntity.BodyBuilder response =
                ResponseEntity.accepted().cacheControl(CacheControl.noStore());
        if (execution.replayed()) {
            return response
                    .header(ApiHeaders.IDEMPOTENCY_REPLAYED, "true")
                    .body(CommandReceiptResponse.from(execution.receipt()));
        }
        return response.body(
                DeletionJobResponse.from(execution.result().orElseThrow()));
    }

    private static ResponseEntity<DeletionJobResponse> okJob(
            OrganizationAccountDeletionApplicationService.DeletionJobView view) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(DeletionJobResponse.from(view));
    }

    private Mono<CommandExecution<OrganizationAccountDeletionApplicationService.DeletionJobView>>
            command(
                    Authentication authentication,
                    OrganizationId organizationId,
                    IdempotencyKey idempotencyKey,
                    ServerWebExchange exchange,
                    Function<
                                    OrganizationCommandContext,
                                    CommandExecution<
                                            OrganizationAccountDeletionApplicationService.DeletionJobView>>
                            action) {
        UUID correlationId = ApiCorrelationIds.resolve(exchange);
        return identityResolver
                .resolve(authentication, organizationId, correlationId)
                .flatMap(
                        access ->
                                blocking(
                                        () ->
                                                action.apply(
                                                        new OrganizationCommandContext(
                                                                access,
                                                                idempotencyKey,
                                                                correlationId,
                                                                Optional.empty()))));
    }

    private <T> Mono<T> query(
            Authentication authentication,
            OrganizationId organizationId,
            ServerWebExchange exchange,
            CorrelatedQuery<T> action) {
        UUID correlationId = ApiCorrelationIds.resolve(exchange);
        return identityResolver
                .resolve(authentication, organizationId, correlationId)
                .flatMap(access -> blocking(() -> action.apply(correlationId, access)));
    }

    /** Query face variant that also needs the correlation id (audit-bearing reads). */
    private interface CorrelatedQuery<T> {
        T apply(UUID correlationId, OrganizationAccessContext access);
    }

    private static <T> Mono<T> blocking(Callable<T> action) {
        return Mono.fromCallable(action).subscribeOn(Schedulers.boundedElastic());
    }

    private static OrganizationId organizationId(String value) {
        try {
            return OrganizationId.from(value);
        } catch (IllegalArgumentException exception) {
            throw invalidIdentifier("organizationId");
        }
    }

    private static UserAccountId accountId(String value) {
        try {
            return UserAccountId.from(value);
        } catch (IllegalArgumentException exception) {
            throw invalidIdentifier("accountId");
        }
    }

    private static AccountDeletionJobId jobId(String value) {
        try {
            return AccountDeletionJobId.from(value);
        } catch (IllegalArgumentException exception) {
            throw invalidIdentifier("jobId");
        }
    }

    private static ApiRequestException invalidIdentifier(String field) {
        return new ApiRequestException(
                HttpStatus.BAD_REQUEST,
                "invalid_request",
                "Request contains an invalid identifier",
                Map.of("field", field));
    }

    public record CreateDeletionRequest(
            @NotBlank String accountId,
            @NotBlank @Size(min = 20, max = REASON_MAX_LENGTH) String reason) {}

    public record ConfirmDeletionRequest(@NotBlank String confirmationToken) {}

    public record DeletionPrecheckResponse(
            String accountId,
            long inFlightTaskExecutions,
            long activeResponsibilities,
            Long openReservations) {

        static DeletionPrecheckResponse from(
                OrganizationAccountDeletionApplicationService.DeletionPrecheckView view) {
            return new DeletionPrecheckResponse(
                    view.accountId().toString(),
                    view.inFlightTaskExecutions(),
                    view.activeResponsibilities(),
                    view.openReservations());
        }
    }

    public record DeletionJobResponse(
            String id,
            String organizationId,
            String accountId,
            String targetPrincipalId,
            String reason,
            String confirmationTokenHash,
            String status,
            String reservationCheck,
            String backupRetentionStatement,
            long version,
            String createdAt,
            String updatedAt,
            List<DeletionItemResponse> items) {

        static DeletionJobResponse from(
                OrganizationAccountDeletionApplicationService.DeletionJobView view) {
            AccountDeletionJob job = view.job();
            return new DeletionJobResponse(
                    job.id().toString(),
                    job.organizationId().toString(),
                    job.accountId().toString(),
                    job.targetPrincipalId().toString(),
                    job.reason(),
                    job.confirmationTokenHash(),
                    job.status().name(),
                    job.reservationCheck(),
                    job.backupRetentionStatement(),
                    job.version(),
                    job.audit().createdAt().toString(),
                    job.audit().updatedAt().toString(),
                    view.items().stream().map(DeletionItemResponse::from).toList());
        }
    }

    public record DeletionItemResponse(
            String id,
            String stepKey,
            String targetRef,
            int expectedCount,
            String state,
            Integer resultCount,
            String errorCode,
            String processedAt,
            long version) {

        static DeletionItemResponse from(AccountDeletionItem item) {
            return new DeletionItemResponse(
                    item.id().toString(),
                    item.stepKey(),
                    item.targetRef(),
                    item.expectedCount(),
                    item.state().name(),
                    item.resultCount().orElse(null),
                    item.errorCode().orElse(null),
                    item.processedAt().map(Object::toString).orElse(null),
                    item.version());
        }
    }
}
