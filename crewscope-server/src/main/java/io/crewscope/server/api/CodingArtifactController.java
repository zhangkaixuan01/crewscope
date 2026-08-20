package io.crewscope.server.api;

import io.crewscope.application.coding.CodingArtifactAccessService;
import io.crewscope.application.coding.CodingArtifactContent;
import io.crewscope.application.coding.CodingArtifactRangeSelection;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.coding.CommandEvidenceId;
import io.crewscope.domain.coding.TestEvidenceId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskId;
import io.crewscope.server.observability.CodingArtifactDownloadRecorder;
import io.crewscope.server.observability.CodingArtifactDownloadRecorder.Kind;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Member-authorized HTTP transfer boundary for Restricted Coding Artifact content. */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/teams/{teamId}/tasks/{taskId}/attempts/{executionId}/coding")
public final class CodingArtifactController {

    private static final Pattern BYTE_RANGE = Pattern.compile("(?i)^bytes=(\\d*)-(\\d*)$");
    private static final int BUFFER_BYTES = 16 * 1024;

    private final CodingArtifactAccessService service;
    private final TeamRequestIdentityResolver identityResolver;
    private final CodingArtifactDownloadRecorder recorder;

    public CodingArtifactController(
            CodingArtifactAccessService service,
            TeamRequestIdentityResolver identityResolver,
            CodingArtifactDownloadRecorder recorder) {
        this.service = service;
        this.identityResolver = identityResolver;
        this.recorder = recorder;
    }

    @GetMapping("/artifacts/patch")
    public Mono<ResponseEntity<Flux<DataBuffer>>> patch(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String taskId,
            @PathVariable String executionId,
            @RequestHeader(name = HttpHeaders.RANGE, required = false) List<String> ranges,
            @RequestParam(required = false) Long offset,
            @RequestParam(required = false) Integer limit,
            Authentication authentication,
            ServerWebExchange exchange) {
        Route route = route(organizationId, teamId, taskId, executionId);
        TransferSelection selection = selection(ranges, offset, limit);
        return transfer(
                authentication,
                exchange,
                route,
                selection,
                Kind.PATCH,
                access -> service.openPatch(
                        access,
                        route.organizationId(),
                        route.teamId(),
                        route.taskId(),
                        route.executionId(),
                        selection.range()),
                "crewscope-" + route.executionId() + "-changes.patch");
    }

    @GetMapping("/commands/{commandEvidenceId}/log")
    public Mono<ResponseEntity<Flux<DataBuffer>>> buildLog(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String taskId,
            @PathVariable String executionId,
            @PathVariable String commandEvidenceId,
            @RequestHeader(name = HttpHeaders.RANGE, required = false) List<String> ranges,
            @RequestParam(required = false) Long offset,
            @RequestParam(required = false) Integer limit,
            Authentication authentication,
            ServerWebExchange exchange) {
        Route route = route(organizationId, teamId, taskId, executionId);
        CommandEvidenceId evidenceId = commandEvidenceId(commandEvidenceId);
        TransferSelection selection = selection(ranges, offset, limit);
        return transfer(
                authentication,
                exchange,
                route,
                selection,
                Kind.BUILD_LOG,
                access -> service.openBuildLog(
                        access,
                        route.organizationId(),
                        route.teamId(),
                        route.taskId(),
                        route.executionId(),
                        evidenceId,
                        selection.range()),
                "crewscope-" + route.executionId() + "-command-" + evidenceId + ".log");
    }

    @GetMapping("/test-evidence/{testEvidenceId}/report")
    public Mono<ResponseEntity<Flux<DataBuffer>>> testReport(
            @PathVariable String organizationId,
            @PathVariable String teamId,
            @PathVariable String taskId,
            @PathVariable String executionId,
            @PathVariable String testEvidenceId,
            @RequestHeader(name = HttpHeaders.RANGE, required = false) List<String> ranges,
            @RequestParam(required = false) Long offset,
            @RequestParam(required = false) Integer limit,
            Authentication authentication,
            ServerWebExchange exchange) {
        Route route = route(organizationId, teamId, taskId, executionId);
        TestEvidenceId evidenceId = testEvidenceId(testEvidenceId);
        TransferSelection selection = selection(ranges, offset, limit);
        return transfer(
                authentication,
                exchange,
                route,
                selection,
                Kind.TEST_REPORT,
                access -> service.openTestReport(
                        access,
                        route.organizationId(),
                        route.teamId(),
                        route.taskId(),
                        route.executionId(),
                        evidenceId,
                        selection.range()),
                "crewscope-" + route.executionId() + "-test-" + evidenceId);
    }

    private Mono<ResponseEntity<Flux<DataBuffer>>> transfer(
            Authentication authentication,
            ServerWebExchange exchange,
            Route route,
            TransferSelection selection,
            Kind kind,
            Function<TeamAccessContext, CodingArtifactContent> opener,
            String filenameStem) {
        UUID correlationId = ApiCorrelationIds.resolve(exchange);
        return identityResolver.resolve(authentication, route.organizationId(), correlationId)
                .flatMap(access -> blocking(() -> opener.apply(access))
                        .map(content -> response(
                                access,
                                exchange,
                                route,
                                correlationId,
                                selection,
                                kind,
                                filenameStem,
                                content)));
    }

    private ResponseEntity<Flux<DataBuffer>> response(
            TeamAccessContext access,
            ServerWebExchange exchange,
            Route route,
            UUID correlationId,
            TransferSelection selection,
            Kind kind,
            String filenameStem,
            CodingArtifactContent content) {
        try {
            MediaType mediaType = MediaType.parseMediaType(content.contentType());
            String filename = filename(filenameStem, kind, mediaType);
            DataBufferFactory buffers = exchange.getResponse().bufferFactory();
            Flux<DataBuffer> body = Flux.using(
                    () -> content,
                    value -> DataBufferUtils.readInputStream(value::stream, buffers, BUFFER_BYTES)
                            .subscribeOn(Schedulers.boundedElastic()),
                    CodingArtifactController::close);
            ResponseEntity.BodyBuilder response = ResponseEntity
                    .status(selection.rangedRequest() ? HttpStatus.PARTIAL_CONTENT : HttpStatus.OK)
                    .cacheControl(CacheControl.noStore())
                    .contentType(mediaType)
                    .contentLength(content.contentLength())
                    .eTag("\"sha256-" + content.contentHash().value() + "\"")
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .header("X-Content-Type-Options", "nosniff")
                    .header("Content-Security-Policy", "sandbox")
                    .header(
                            HttpHeaders.CONTENT_DISPOSITION,
                            ContentDisposition.attachment()
                                    .filename(filename, StandardCharsets.UTF_8)
                                    .build()
                                    .toString());
            if (selection.rangedRequest()) {
                response.header(
                        HttpHeaders.CONTENT_RANGE,
                        "bytes " + content.startInclusive() + "-" + (content.endExclusive() - 1)
                                + "/" + content.totalSize());
            }
            recorder.record(
                    kind,
                    access,
                    route.organizationId(),
                    route.teamId(),
                    route.taskId(),
                    route.executionId(),
                    correlationId,
                    content,
                    selection.rangedRequest());
            return response.body(body);
        } catch (RuntimeException failure) {
            close(content);
            throw failure;
        }
    }

    private static String filename(String stem, Kind kind, MediaType mediaType) {
        if (kind != Kind.TEST_REPORT) {
            return stem;
        }
        String extension = MediaType.APPLICATION_JSON.includes(mediaType)
                ? ".json"
                : (MediaType.APPLICATION_XML.includes(mediaType) || MediaType.TEXT_XML.includes(mediaType))
                        ? ".xml"
                        : ".txt";
        return stem + extension;
    }

    private static TransferSelection selection(List<String> ranges, Long offset, Integer limit) {
        List<String> headers = ranges == null ? List.of() : List.copyOf(ranges);
        boolean pageRequested = offset != null || limit != null;
        if (!headers.isEmpty() && pageRequested) {
            throw invalidRange("Range", "Range header cannot be combined with offset and limit");
        }
        if (headers.size() > 1) {
            throw invalidRange("Range", "Only one byte Range is supported");
        }
        if (pageRequested) {
            if (offset == null || limit == null || offset < 0 || limit < 1) {
                throw invalidRange("offset", "offset and limit must define a positive byte page");
            }
            try {
                return new TransferSelection(
                        CodingArtifactRangeSelection.between(offset, Math.addExact(offset, limit.longValue())),
                        true);
            } catch (ArithmeticException exception) {
                throw invalidRange("limit", "Byte page coordinates overflow");
            }
        }
        if (headers.isEmpty()) {
            return new TransferSelection(CodingArtifactRangeSelection.whole(), false);
        }
        String header = headers.get(0);
        Matcher matcher = BYTE_RANGE.matcher(header == null ? "" : header.strip());
        if (!matcher.matches() || (matcher.group(1).isEmpty() && matcher.group(2).isEmpty())) {
            throw invalidRange("Range", "Range must contain one valid bytes interval");
        }
        try {
            if (matcher.group(1).isEmpty()) {
                return new TransferSelection(
                        CodingArtifactRangeSelection.suffix(Long.parseLong(matcher.group(2))), true);
            }
            long start = Long.parseLong(matcher.group(1));
            if (matcher.group(2).isEmpty()) {
                return new TransferSelection(CodingArtifactRangeSelection.from(start), true);
            }
            long endInclusive = Long.parseLong(matcher.group(2));
            if (endInclusive < start) {
                throw invalidRange("Range", "Range end must not be before its start");
            }
            return new TransferSelection(
                    CodingArtifactRangeSelection.between(start, Math.addExact(endInclusive, 1)), true);
        } catch (NumberFormatException | ArithmeticException exception) {
            throw invalidRange("Range", "Range coordinates are invalid");
        }
    }

    private static Route route(String organization, String team, String task, String execution) {
        try {
            return new Route(
                    OrganizationId.from(organization),
                    TeamId.from(team),
                    TaskId.from(task),
                    TaskExecutionId.from(execution));
        } catch (IllegalArgumentException exception) {
            throw invalidIdentifier("route");
        }
    }

    private static CommandEvidenceId commandEvidenceId(String value) {
        try {
            return CommandEvidenceId.from(value);
        } catch (IllegalArgumentException exception) {
            throw invalidIdentifier("commandEvidenceId");
        }
    }

    private static TestEvidenceId testEvidenceId(String value) {
        try {
            return TestEvidenceId.from(value);
        } catch (IllegalArgumentException exception) {
            throw invalidIdentifier("testEvidenceId");
        }
    }

    private static ApiRequestException invalidRange(String field, String message) {
        return new ApiRequestException(
                HttpStatus.BAD_REQUEST,
                "invalid_range",
                message,
                Map.of("field", field));
    }

    private static ApiRequestException invalidIdentifier(String field) {
        return new ApiRequestException(
                HttpStatus.BAD_REQUEST,
                "invalid_request",
                "Request contains an invalid identifier",
                Map.of("field", field));
    }

    private static <T> Mono<T> blocking(Callable<T> action) {
        return Mono.fromCallable(action).subscribeOn(Schedulers.boundedElastic());
    }

    private static void close(CodingArtifactContent content) {
        try {
            content.close();
        } catch (IOException ignored) {
            // The transfer outcome is already terminal; close failures cannot change disclosed bytes.
        }
    }

    private record Route(
            OrganizationId organizationId,
            TeamId teamId,
            TaskId taskId,
            TaskExecutionId executionId) {}

    private record TransferSelection(
            CodingArtifactRangeSelection range, boolean rangedRequest) {}
}
