package io.crewscope.server.api;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.application.organization.OrganizationAccessContext;
import io.crewscope.application.organization.OrganizationCommandContext;
import io.crewscope.application.organization.governance.DataExportArtifact;
import io.crewscope.application.organization.governance.OrganizationExportApplicationService;
import io.crewscope.domain.organization.governance.DataExportItem;
import io.crewscope.domain.organization.governance.DataExportJob;
import io.crewscope.domain.organization.governance.DataExportJobId;
import io.crewscope.domain.shared.id.OrganizationId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Function;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.ObjectMapper;

/**
 * HTTP boundary of the controlled organization data export (M12-A01). create is an idempotent
 * command; process/cancel are job-state-machine steps that re-enter safely; the artifacts face
 * is a dedicated vendor media type whose every download — allowed or denied — is audited by the
 * application service before any byte leaves.
 */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/data-exports")
public final class OrganizationExportController {

    /** The frozen download face of a completed export, distinct from casual JSON reads. */
    private static final MediaType GOVERNANCE_EXPORT_MEDIA_TYPE =
            MediaType.parseMediaType("application/vnd.crewscope.governance-export+json");

    private final OrganizationExportApplicationService service;
    private final OrganizationRequestIdentityResolver identityResolver;
    private final ObjectMapper objectMapper;

    public OrganizationExportController(
            OrganizationExportApplicationService service,
            OrganizationRequestIdentityResolver identityResolver,
            ObjectMapper objectMapper) {
        this.service = service;
        this.identityResolver = identityResolver;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    public Mono<ResponseEntity<Object>> create(
            @PathVariable String organizationId,
            @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
            @Valid @RequestBody CreateExportRequest request,
            Authentication authentication,
            ServerWebExchange exchange) {
        OrganizationId organization = organizationId(organizationId);
        IdempotencyKey idempotencyKey = ApiHeaders.requireIdempotencyKey(key);
        UUID correlationId = ApiCorrelationIds.resolve(exchange);
        return command(
                        authentication,
                        organization,
                        idempotencyKey,
                        exchange,
                        context -> service.create(context, organization, request.includeVectors()))
                .map(execution -> acceptedJob(execution, objectMapper));
    }

    /** Settles every PENDING item; an interrupted run resumes at the first unsettled one. */
    @PostMapping("/{jobId}/process")
    public Mono<ResponseEntity<ExportJobResponse>> process(
            @PathVariable String organizationId,
            @PathVariable String jobId,
            Authentication authentication,
            ServerWebExchange exchange) {
        OrganizationId organization = organizationId(organizationId);
        DataExportJobId job = jobId(jobId);
        return query(
                        authentication,
                        organization,
                        exchange,
                        (correlationId, access) ->
                                service.process(access, organization, job, correlationId))
                .map(view -> okJob(view, objectMapper));
    }

    @PostMapping("/{jobId}/cancel")
    public Mono<ResponseEntity<ExportJobResponse>> cancel(
            @PathVariable String organizationId,
            @PathVariable String jobId,
            Authentication authentication,
            ServerWebExchange exchange) {
        OrganizationId organization = organizationId(organizationId);
        DataExportJobId job = jobId(jobId);
        return query(
                        authentication,
                        organization,
                        exchange,
                        (correlationId, access) ->
                                service.cancel(access, organization, job, correlationId))
                .map(view -> okJob(view, objectMapper));
    }

    /** The reconciliation surface: job + items + the frozen manifest once completed. */
    @GetMapping("/{jobId}")
    public Mono<ResponseEntity<ExportJobResponse>> get(
            @PathVariable String organizationId,
            @PathVariable String jobId,
            Authentication authentication,
            ServerWebExchange exchange) {
        OrganizationId organization = organizationId(organizationId);
        DataExportJobId job = jobId(jobId);
        return query(
                        authentication,
                        organization,
                        exchange,
                        (correlationId, access) -> service.get(access, organization, job))
                .map(view -> okJob(view, objectMapper));
    }

    @GetMapping(
            value = "/{jobId}/artifacts",
            produces = "application/vnd.crewscope.governance-export+json")
    public Mono<ResponseEntity<ExportArtifactsResponse>> artifacts(
            @PathVariable String organizationId,
            @PathVariable String jobId,
            Authentication authentication,
            ServerWebExchange exchange) {
        OrganizationId organization = organizationId(organizationId);
        DataExportJobId job = jobId(jobId);
        return query(
                        authentication,
                        organization,
                        exchange,
                        (correlationId, access) ->
                                service.fetchArtifacts(access, organization, job, correlationId))
                .map(
                        view ->
                                ResponseEntity.ok()
                                        .cacheControl(CacheControl.noStore())
                                        .contentType(GOVERNANCE_EXPORT_MEDIA_TYPE)
                                        .header(
                                                HttpHeaders.CONTENT_DISPOSITION,
                                                ContentDisposition.attachment()
                                                        .filename(
                                                                "export-" + job.value() + ".json",
                                                                StandardCharsets.UTF_8)
                                                        .build()
                                                        .toString())
                                        .body(ExportArtifactsResponse.from(view, objectMapper)));
    }

    /** 202 with the queued job on first execution; the receipt alone on an idempotent replay. */
    private static ResponseEntity<Object> acceptedJob(
            CommandExecution<OrganizationExportApplicationService.ExportJobView> execution,
            ObjectMapper mapper) {
        ResponseEntity.BodyBuilder response =
                ResponseEntity.accepted().cacheControl(CacheControl.noStore());
        if (execution.replayed()) {
            return response
                    .header(ApiHeaders.IDEMPOTENCY_REPLAYED, "true")
                    .body(CommandReceiptResponse.from(execution.receipt()));
        }
        return response.body(ExportJobResponse.from(execution.result().orElseThrow(), mapper));
    }

    private static ResponseEntity<ExportJobResponse> okJob(
            OrganizationExportApplicationService.ExportJobView view, ObjectMapper mapper) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ExportJobResponse.from(view, mapper));
    }

    private Mono<CommandExecution<OrganizationExportApplicationService.ExportJobView>> command(
            Authentication authentication,
            OrganizationId organizationId,
            IdempotencyKey idempotencyKey,
            ServerWebExchange exchange,
            Function<
                            OrganizationCommandContext,
                            CommandExecution<
                                    OrganizationExportApplicationService.ExportJobView>>
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

    private static DataExportJobId jobId(String value) {
        try {
            return DataExportJobId.from(value);
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

    public record CreateExportRequest(@NotNull Boolean includeVectors) {}

    public record ExportJobResponse(
            String id,
            String organizationId,
            String requestedByPrincipalId,
            String status,
            boolean includeVectors,
            Object manifest,
            long version,
            String createdAt,
            String updatedAt,
            List<ExportItemResponse> items) {

        static ExportJobResponse from(
                OrganizationExportApplicationService.ExportJobView view, ObjectMapper mapper) {
            DataExportJob job = view.job();
            return new ExportJobResponse(
                    job.id().toString(),
                    job.organizationId().toString(),
                    job.requestedByPrincipalId().toString(),
                    job.status().name(),
                    job.includeVectors(),
                    job.manifest()
                            .map(manifest -> mapper.readValue(manifest, Object.class))
                            .orElse(null),
                    job.version(),
                    job.audit().createdAt().toString(),
                    job.audit().updatedAt().toString(),
                    view.items().stream().map(ExportItemResponse::from).toList());
        }
    }

    public record ExportItemResponse(
            String id,
            String resourceFamily,
            int batchSequence,
            int rowCount,
            String state,
            String checksum,
            String errorCode,
            String processedAt,
            long version) {

        static ExportItemResponse from(DataExportItem item) {
            return new ExportItemResponse(
                    item.id().toString(),
                    item.resourceFamily(),
                    item.batchSequence(),
                    item.rowCount(),
                    item.state().name(),
                    item.checksum().orElse(null),
                    item.errorCode().orElse(null),
                    item.processedAt().map(Object::toString).orElse(null),
                    item.version());
        }
    }

    public record ExportArtifactsResponse(Object manifest, List<ArtifactResponse> artifacts) {

        static ExportArtifactsResponse from(
                OrganizationExportApplicationService.ExportArtifactsView view, ObjectMapper mapper) {
            return new ExportArtifactsResponse(
                    view.job().manifest().isEmpty()
                            ? Map.of()
                            : mapper.readValue(view.job().manifest().orElseThrow(), Object.class),
                    view.artifacts().stream()
                            .map(artifact -> ArtifactResponse.from(artifact, mapper))
                            .toList());
        }
    }

    public record ArtifactResponse(
            String id,
            String itemId,
            int artifactSequence,
            int byteSize,
            String checksum,
            List<Object> rows) {

        static ArtifactResponse from(DataExportArtifact artifact, ObjectMapper mapper) {
            return new ArtifactResponse(
                    artifact.id().toString(),
                    artifact.itemId().toString(),
                    artifact.artifactSequence(),
                    artifact.byteSize(),
                    artifact.checksum(),
                    artifact.content().lines()
                            .map(line -> (Object) mapper.readValue(line, Object.class))
                            .toList());
        }
    }
}
