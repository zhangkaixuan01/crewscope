package io.crewscope.server.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.crewscope.application.coding.CodingArtifactAccessService;
import io.crewscope.application.coding.CodingArtifactContent;
import io.crewscope.application.coding.CodingArtifactRangeNotSatisfiableException;
import io.crewscope.application.coding.CodingArtifactRangeSelection;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.id.ArtifactId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.task.RuntimeContentHash;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskId;
import io.crewscope.infrastructure.workspace.repository.CodingArtifactError;
import io.crewscope.infrastructure.workspace.repository.CodingArtifactException;
import io.crewscope.server.observability.CodingArtifactDownloadRecorder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/** HTTP Range/page, safe headers, error and audit contract for M4-A06. */
class CodingArtifactControllerM4A06Test {

    private final OrganizationId organizationId = OrganizationId.generate();
    private final TeamId teamId = TeamId.generate();
    private final TaskId taskId = TaskId.generate();
    private final TaskExecutionId executionId = TaskExecutionId.generate();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private CodingArtifactAccessService service;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        service = mock(CodingArtifactAccessService.class);
        Principal actor = mock(Principal.class);
        when(actor.id()).thenReturn(PrincipalId.generate());
        TeamAccessContext access = new TeamAccessContext(actor, false);
        TeamRequestIdentityResolver resolver = (authentication, organization, correlationId) ->
                Mono.just(access);
        CodingArtifactDownloadRecorder recorder = new CodingArtifactDownloadRecorder(registry);
        client = WebTestClient.bindToController(
                        new CodingArtifactController(service, resolver, recorder))
                .controllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void streamsOneExactRangeWithSafeDownloadHeadersAndAudit() {
        TestContent content = content("cret", "text/plain;charset=utf-8", 10, 2, 6);
        when(service.openPatch(any(), any(), any(), any(), any(), any())).thenReturn(content);

        client.get()
                .uri(root() + "/artifacts/patch")
                .header(HttpHeaders.RANGE, "bytes=2-5")
                .exchange()
                .expectStatus().isEqualTo(206)
                .expectHeader().valueEquals(HttpHeaders.CONTENT_RANGE, "bytes 2-5/10")
                .expectHeader().valueEquals(HttpHeaders.ACCEPT_RANGES, "bytes")
                .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
                .expectHeader().valueEquals("X-Content-Type-Options", "nosniff")
                .expectHeader().valueMatches(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment;.*crewscope-" + executionId + "-changes\\.patch.*")
                .expectBody(byte[].class).isEqualTo("cret".getBytes(StandardCharsets.UTF_8));

        assertTrue(content.closed.get());
        assertEquals(
                1.0,
                registry.get(CodingArtifactDownloadRecorder.REQUESTS)
                        .tag("kind", "patch")
                        .tag("mode", "partial")
                        .counter()
                        .count());
        ArgumentCaptor<CodingArtifactRangeSelection> range =
                ArgumentCaptor.forClass(CodingArtifactRangeSelection.class);
        org.mockito.Mockito.verify(service).openPatch(
                any(), any(), any(), any(), any(), range.capture());
        assertEquals(CodingArtifactRangeSelection.between(2, 6), range.getValue());
    }

    @Test
    void supportsBytePaginationAndOpenEndedRanges() {
        when(service.openPatch(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> content("page", "text/plain", 100, 20, 24));

        client.get().uri(root() + "/artifacts/patch?offset=20&limit=4")
                .exchange().expectStatus().isEqualTo(206)
                .expectBody(byte[].class).isEqualTo("page".getBytes(StandardCharsets.UTF_8));
        client.get().uri(root() + "/artifacts/patch")
                .header(HttpHeaders.RANGE, "bytes=90-")
                .exchange().expectStatus().isEqualTo(206);

        ArgumentCaptor<CodingArtifactRangeSelection> range =
                ArgumentCaptor.forClass(CodingArtifactRangeSelection.class);
        org.mockito.Mockito.verify(service, org.mockito.Mockito.times(2)).openPatch(
                any(), any(), any(), any(), any(), range.capture());
        assertEquals(CodingArtifactRangeSelection.between(20, 24), range.getAllValues().get(0));
        assertEquals(CodingArtifactRangeSelection.from(90), range.getAllValues().get(1));
    }

    @Test
    void rejectsAmbiguousRangesBeforeAuthorizationOrContentAccess() {
        client.get().uri(root() + "/artifacts/patch?offset=0&limit=4")
                .header(HttpHeaders.RANGE, "bytes=0-3")
                .exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("invalid_range");
        client.get().uri(root() + "/artifacts/patch")
                .header(HttpHeaders.RANGE, "bytes=0-1,4-5")
                .exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("invalid_range");

        verifyNoInteractions(service);
    }

    @Test
    void returnsStandardUnsatisfiedRangeWithoutLeakingContent() {
        when(service.openPatch(any(), any(), any(), any(), any(), any()))
                .thenThrow(new CodingArtifactRangeNotSatisfiableException(12));

        client.get().uri(root() + "/artifacts/patch")
                .header(HttpHeaders.RANGE, "bytes=12-20")
                .exchange().expectStatus().isEqualTo(416)
                .expectHeader().valueEquals(HttpHeaders.CONTENT_RANGE, "bytes */12")
                .expectBody()
                .jsonPath("$.code").isEqualTo("coding_artifact_range_not_satisfiable")
                .jsonPath("$.details.totalSize").isEqualTo("12");
    }

    @Test
    void mapsResponseBudgetAndConcurrentCapacityToStableErrors() {
        when(service.openPatch(any(), any(), any(), any(), any(), any()))
                .thenThrow(new CodingArtifactException(
                        CodingArtifactError.SIZE_LIMIT_EXCEEDED, "safe"))
                .thenThrow(new CodingArtifactException(
                        CodingArtifactError.TOO_MANY_CONCURRENT_READS, "safe"));

        client.get().uri(root() + "/artifacts/patch").exchange()
                .expectStatus().isEqualTo(413)
                .expectBody().jsonPath("$.code").isEqualTo("coding_artifact_response_too_large");
        client.get().uri(root() + "/artifacts/patch").exchange()
                .expectStatus().isEqualTo(429)
                .expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "1")
                .expectBody().jsonPath("$.code").isEqualTo("coding_artifact_download_busy");
    }

    @Test
    void exposesOnlyPurposeBoundRoutesAndSafeTestReportNames() {
        TestContent content = content("{}", "application/json", 2, 0, 2);
        when(service.openTestReport(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(content);
        String evidenceId = java.util.UUID.randomUUID().toString();

        client.get().uri(root() + "/test-evidence/" + evidenceId + "/report")
                .exchange().expectStatus().isOk()
                .expectHeader().valueMatches(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment;.*crewscope-" + executionId + "-test-" + evidenceId
                                + "\\.json.*");
        client.get().uri(root() + "/artifacts/" + java.util.UUID.randomUUID())
                .exchange().expectStatus().isNotFound();
    }

    private String root() {
        return "/api/v1/organizations/" + organizationId
                + "/teams/" + teamId
                + "/tasks/" + taskId
                + "/attempts/" + executionId
                + "/coding";
    }

    private static TestContent content(
            String body, String contentType, long total, long start, long end) {
        return new TestContent(
                body.getBytes(StandardCharsets.UTF_8),
                contentType,
                total,
                start,
                end);
    }

    private static final class TestContent implements CodingArtifactContent {

        private final byte[] bytes;
        private final String contentType;
        private final long total;
        private final long start;
        private final long end;
        private final InputStream stream;
        private final AtomicBoolean closed = new AtomicBoolean();

        private TestContent(byte[] bytes, String contentType, long total, long start, long end) {
            this.bytes = bytes;
            this.contentType = contentType;
            this.total = total;
            this.start = start;
            this.end = end;
            this.stream = new ByteArrayInputStream(bytes);
        }

        @Override
        public ArtifactId artifactId() {
            return ArtifactId.generate();
        }

        @Override
        public String contentType() {
            return contentType;
        }

        @Override
        public RuntimeContentHash contentHash() {
            return RuntimeContentHash.sha256(new String(bytes, StandardCharsets.UTF_8));
        }

        @Override
        public long totalSize() {
            return total;
        }

        @Override
        public long startInclusive() {
            return start;
        }

        @Override
        public long endExclusive() {
            return end;
        }

        @Override
        public InputStream stream() {
            return stream;
        }

        @Override
        public void close() throws IOException {
            stream.close();
            closed.set(true);
        }
    }
}
