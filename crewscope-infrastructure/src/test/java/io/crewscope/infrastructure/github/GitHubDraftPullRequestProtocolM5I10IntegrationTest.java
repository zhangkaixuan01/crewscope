package io.crewscope.infrastructure.github;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.crewscope.application.github.GitHubDraftPullRequestErrorCode;
import io.crewscope.application.github.GitHubDraftPullRequestException;
import io.crewscope.application.github.GitHubDraftPullRequestOutcome;
import io.crewscope.application.github.GitHubPullRequestState;
import io.crewscope.domain.action.CreateDraftPullRequestActionParameters;
import io.crewscope.domain.action.ExternalRepositoryId;
import io.crewscope.domain.coding.RepositoryBranchName;
import io.crewscope.domain.coding.RepositoryCommitId;
import io.crewscope.domain.provider.ConnectionId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Loopback proof for exact Draft PR idempotency and unknown-write reconciliation. */
@Tag("integration")
class GitHubDraftPullRequestProtocolM5I10IntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TOKEN = "github_pat_m5_i10_protocol_secret";
    private static final String HEAD_SHA = "a".repeat(40);
    private static final UtcTimestamp NOW = UtcTimestamp.from(
            Instant.parse("2026-08-23T12:00:00Z"));

    @Test
    void recoversLostResponseAndNeverCreatesASecondPullRequest() throws Exception {
        try (GitHubStub github = GitHubStub.start()) {
            GitHubDraftPullRequestProtocol protocol = protocol(github.baseUri());
            CreateDraftPullRequestActionParameters action = action("Confirmed title");
            github.loseNextCreateResponse();

            var recovered = protocol.ensure(action, "crewscope/repository-a", bytes(TOKEN));
            var repeated = protocol.ensure(action, "crewscope/repository-a", bytes(TOKEN));

            assertEquals(GitHubDraftPullRequestOutcome.RECOVERED_AFTER_UNKNOWN,
                    recovered.outcome());
            assertEquals(GitHubDraftPullRequestOutcome.ALREADY_PRESENT, repeated.outcome());
            assertEquals(recovered.pullRequestId(), repeated.pullRequestId());
            assertEquals(1, github.createCount());
            assertEquals("101:pull-request:1", recovered.externalIdentity().businessKey());

            GitHubDraftPullRequestException conflict = assertThrows(
                    GitHubDraftPullRequestException.class,
                    () -> protocol.ensure(
                            action("Changed after confirmation"),
                            "crewscope/repository-a",
                            bytes(TOKEN)));
            assertEquals(GitHubDraftPullRequestErrorCode.PULL_REQUEST_CONFLICT, conflict.code());
            assertEquals(1, github.createCount());
            assertFalse((conflict + " " + conflict.getCause()).contains(TOKEN));
            assertFalse((conflict + " " + conflict.getCause()).contains(github.baseUri().toString()));
        }
    }

    @Test
    void rejectsRemoteHeadDriftBeforeAnyWrite() throws Exception {
        try (GitHubStub github = GitHubStub.start()) {
            github.remoteHead("b".repeat(40));
            GitHubDraftPullRequestException conflict = assertThrows(
                    GitHubDraftPullRequestException.class,
                    () -> protocol(github.baseUri()).ensure(
                            action("Confirmed title"),
                            "crewscope/repository-a",
                            bytes(TOKEN)));

            assertEquals(GitHubDraftPullRequestErrorCode.REMOTE_HEAD_CONFLICT, conflict.code());
            assertEquals(0, github.createCount());
        }
    }

    @Test
    void observesCloseAndReopenWithoutCreatingAnotherPullRequest() throws Exception {
        try (GitHubStub github = GitHubStub.start()) {
            GitHubDraftPullRequestProtocol protocol = protocol(github.baseUri());
            CreateDraftPullRequestActionParameters action = action("Confirmed title");
            assertEquals(GitHubDraftPullRequestOutcome.CREATED,
                    protocol.ensure(action, "crewscope/repository-a", bytes(TOKEN)).outcome());

            github.state("closed", false, "2026-08-23T11:01:00Z");
            assertEquals(GitHubPullRequestState.CLOSED,
                    protocol.ensure(action, "crewscope/repository-a", bytes(TOKEN)).state());
            github.state("open", false, "2026-08-23T11:02:00Z");
            assertEquals(GitHubPullRequestState.OPEN,
                    protocol.ensure(action, "crewscope/repository-a", bytes(TOKEN)).state());

            assertEquals(1, github.createCount());
        }
    }

    @Test
    void queryOnlyPathReturnsExistingOrMissingWithoutSendingPost() throws Exception {
        try (GitHubStub github = GitHubStub.start()) {
            GitHubDraftPullRequestProtocol protocol = protocol(github.baseUri());
            CreateDraftPullRequestActionParameters action = action("Confirmed title");

            assertEquals(java.util.Optional.empty(),
                    protocol.query(action, "crewscope/repository-a", bytes(TOKEN)));
            assertEquals(0, github.createCount());

            protocol.ensure(action, "crewscope/repository-a", bytes(TOKEN));
            assertEquals(1, github.createCount());
            assertEquals("9001", protocol.query(
                            action, "crewscope/repository-a", bytes(TOKEN))
                    .orElseThrow()
                    .pullRequestId());
            assertEquals(1, github.createCount());
        }
    }

    @Test
    void treatsUnmatchedValidationRejectionAsDefiniteWithoutBlindRetry() throws Exception {
        try (GitHubStub github = GitHubStub.start()) {
            github.rejectNextCreate();
            GitHubDraftPullRequestException rejected = assertThrows(
                    GitHubDraftPullRequestException.class,
                    () -> protocol(github.baseUri()).ensure(
                            action("Confirmed title"),
                            "crewscope/repository-a",
                            bytes(TOKEN)));

            assertEquals(GitHubDraftPullRequestErrorCode.VALIDATION_FAILED, rejected.code());
            assertEquals(0, github.createCount());
        }
    }

    private static GitHubDraftPullRequestProtocol protocol(URI apiBaseUri) {
        return new GitHubDraftPullRequestProtocol(
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(2))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                JSON,
                apiBaseUri,
                URI.create("https://github.com"),
                Duration.ofSeconds(5),
                TimeProvider.from(java.time.Clock.fixed(NOW.value(), java.time.ZoneOffset.UTC)),
                true);
    }

    private static CreateDraftPullRequestActionParameters action(String title) {
        return new CreateDraftPullRequestActionParameters(
                new ExternalRepositoryId("101"),
                new RepositoryBranchName("crewscope/task-101"),
                new RepositoryBranchName("main"),
                new RepositoryCommitId(HEAD_SHA),
                title,
                "CrewScope delivery evidence",
                true,
                new ConnectionId(UUID.fromString("00000000-0000-0000-0000-000000000101")));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static final class GitHubStub implements AutoCloseable {

        private final HttpServer server;
        private final AtomicInteger createCount = new AtomicInteger();
        private final AtomicBoolean loseNextResponse = new AtomicBoolean();
        private final AtomicBoolean rejectNextCreate = new AtomicBoolean();
        private final List<PullRequest> pullRequests = new ArrayList<>();
        private String remoteHead = HEAD_SHA;
        private String state = "open";
        private boolean merged;
        private String updatedAt = "2026-08-23T11:00:00Z";

        private GitHubStub(HttpServer server) {
            this.server = server;
        }

        static GitHubStub start() throws IOException {
            HttpServer server = HttpServer.create(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            GitHubStub stub = new GitHubStub(server);
            server.createContext("/", stub::handle);
            server.start();
            return stub;
        }

        URI baseUri() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        }

        void loseNextCreateResponse() {
            loseNextResponse.set(true);
        }

        void remoteHead(String value) {
            remoteHead = value;
        }

        void rejectNextCreate() {
            rejectNextCreate.set(true);
        }

        void state(String value, boolean merged, String updatedAt) {
            state = value;
            this.merged = merged;
            this.updatedAt = updatedAt;
        }

        int createCount() {
            return createCount.get();
        }

        private void handle(HttpExchange exchange) throws IOException {
            if (!("Bearer " + TOKEN).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                send(exchange, 401, Map.of("message", "fixture-token"));
                return;
            }
            String path = exchange.getRequestURI().getRawPath();
            if (path.endsWith("/git/ref/heads/crewscope/task-101")) {
                send(exchange, 200, Map.of("object", Map.of("sha", remoteHead)));
                return;
            }
            if (!path.endsWith("/pulls")) {
                send(exchange, 404, Map.of("message", "missing"));
                return;
            }
            if ("GET".equals(exchange.getRequestMethod())) {
                send(exchange, 200, pullRequests.stream().map(this::response).toList());
                return;
            }
            if (rejectNextCreate.compareAndSet(true, false)) {
                send(exchange, 422, Map.of("message", "fixture validation rejection"));
                return;
            }
            JsonNode request = JSON.readTree(exchange.getRequestBody());
            PullRequest created = new PullRequest(
                    "9001",
                    createCount.incrementAndGet(),
                    request.path("head").asText(),
                    request.path("base").asText(),
                    request.path("title").asText(),
                    request.path("body").asText(),
                    request.path("draft").asBoolean());
            pullRequests.add(created);
            if (loseNextResponse.compareAndSet(true, false)) {
                exchange.close();
                return;
            }
            send(exchange, 201, response(created));
        }

        private Map<String, Object> response(PullRequest value) {
            java.util.HashMap<String, Object> result = new java.util.HashMap<>();
            result.put("id", value.id());
            result.put("number", value.number());
            result.put("html_url", "https://github.com/crewscope/repository-a/pull/" + value.number());
            result.put("head", Map.of(
                    "ref", value.head(), "sha", remoteHead, "repo", Map.of("id", 101)));
            result.put("base", Map.of("ref", value.base(), "repo", Map.of("id", 101)));
            result.put("title", value.title());
            result.put("body", value.body());
            result.put("draft", value.draft());
            result.put("state", state);
            result.put("merged", merged);
            result.put("updated_at", updatedAt);
            return result;
        }

        private static void send(HttpExchange exchange, int status, Object body) throws IOException {
            byte[] payload = JSON.writeValueAsBytes(body);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private record PullRequest(
            String id,
            long number,
            String head,
            String base,
            String title,
            String body,
            boolean draft) {}
}
