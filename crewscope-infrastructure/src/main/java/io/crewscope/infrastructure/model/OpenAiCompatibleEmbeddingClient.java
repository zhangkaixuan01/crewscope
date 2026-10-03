package io.crewscope.infrastructure.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.crewscope.application.embedding.EmbeddingClient;
import io.crewscope.application.model.ProviderCredentialHandle;
import io.crewscope.domain.model.ModelConnection;
import io.crewscope.domain.model.ModelConnectionHealthFailureCode;
import io.crewscope.domain.model.ModelTokenUsage;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Minimal OpenAI-compatible {@code POST /embeddings} transport (M10-I01a, S01 §3.2).
 * Deliberately shares none of the chat-completions adapter: the measured provider facts
 * (batch cap 10, per-item 33 000 characters, dimension 1024, usage echo) are enforced at
 * the application Port, while this adapter owns only the wire shape, the bounded
 * 429/5xx retry with {@code Retry-After} precedence, and sanitized failure mapping.
 *
 * <p>Every real HTTP attempt reports exactly one {@link Attempt} — including attempts of
 * a delivery that ultimately fails — so no spent tokens escape the usage ledger. Provider
 * response bodies and the bearer secret never cross this boundary into exception
 * messages or results.
 */
public final class OpenAiCompatibleEmbeddingClient implements EmbeddingClient {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration MAX_RETRY_DELAY = Duration.ofSeconds(30);
    private static final int MAX_ATTEMPT_CEILING = 5;

    private final Duration connectTimeout;
    private final Duration requestTimeout;
    private final int maxAttempts;
    private final Duration retryBaseDelay;

    public OpenAiCompatibleEmbeddingClient(
            Duration connectTimeout,
            Duration requestTimeout,
            int maxAttempts,
            Duration retryBaseDelay) {
        this.connectTimeout = requirePositive(connectTimeout, "connectTimeout");
        if (requestTimeout == null
                || requestTimeout.compareTo(MINIMUM_REQUEST_TIMEOUT) < 0) {
            throw new IllegalArgumentException(
                    "requestTimeout must be at least " + MINIMUM_REQUEST_TIMEOUT);
        }
        this.requestTimeout = requestTimeout;
        if (maxAttempts < 1 || maxAttempts > MAX_ATTEMPT_CEILING) {
            throw new IllegalArgumentException(
                    "maxAttempts must be between 1 and " + MAX_ATTEMPT_CEILING);
        }
        this.maxAttempts = maxAttempts;
        this.retryBaseDelay = requirePositive(retryBaseDelay, "retryBaseDelay");
    }

    @Override
    public EmbeddingCall embed(
            ModelConnection connection,
            EmbeddingRequest request,
            ProviderCredentialHandle credentialHandle) {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(credentialHandle, "credentialHandle");
        return credentialHandle.useSecret(
                secret -> sendWithRetries(connection, request, secret));
    }

    // ---------------------------------------------------------------- wire exchange

    private EmbeddingCall sendWithRetries(
            ModelConnection connection, EmbeddingRequest request, byte[] secret) {
        List<Attempt> attempts = new ArrayList<>();
        ModelConnectionHealthFailureCode lastFailure =
                ModelConnectionHealthFailureCode.PROVIDER_REJECTED;
        Duration backoff = retryBaseDelay;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (attempt > 1) {
                sleep(backoff);
            }
            SingleAttempt outcome = send(connection, request, secret);
            attempts.add(new Attempt(attempt, outcome.delivered(), outcome.usage()));
            if (outcome.delivered()) {
                return new EmbeddingCall(
                        true, Optional.empty(), outcome.vectors(), List.copyOf(attempts));
            }
            lastFailure = outcome.failureCode();
            if (!outcome.retryable()) {
                break;
            }
            backoff = outcome.retryAfter()
                    .map(delay -> delay.compareTo(MAX_RETRY_DELAY) > 0 ? MAX_RETRY_DELAY : delay)
                    .orElse(backoff.multipliedBy(2));
        }
        return new EmbeddingCall(
                false, Optional.of(lastFailure), List.of(), List.copyOf(attempts));
    }

    private SingleAttempt send(
            ModelConnection connection, EmbeddingRequest request, byte[] secret) {
        ObjectNode body = JSON.createObjectNode();
        body.put("model", request.model());
        ArrayNode input = body.putArray("input");
        request.input().forEach(input::add);
        body.put("dimensions", request.dimensions());
        HttpRequest httpRequest = HttpRequest.newBuilder(embeddingsUri(connection))
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + new String(secret, StandardCharsets.UTF_8))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        // Each attempt gets its own short-lived client: a pooled connection a middlebox
        // already cut silently must not burn the retry budget (health-probe defect 22).
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                // A redirect must never be able to move the bearer credential off-host.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        try {
            HttpResponse<String> response =
                    httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            return interpret(response, request);
        } catch (java.net.http.HttpTimeoutException exception) {
            return SingleAttempt.failure(ModelConnectionHealthFailureCode.TIMEOUT);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return SingleAttempt.failure(ModelConnectionHealthFailureCode.TIMEOUT);
        } catch (IOException exception) {
            return SingleAttempt.failure(ModelConnectionHealthFailureCode.ENDPOINT_UNREACHABLE);
        } catch (RuntimeException exception) {
            return SingleAttempt.failure(ModelConnectionHealthFailureCode.PROVIDER_REJECTED);
        }
    }

    private SingleAttempt interpret(HttpResponse<String> response, EmbeddingRequest request) {
        int status = response.statusCode();
        if (status == 429) {
            return SingleAttempt.retryableFailure(
                    ModelConnectionHealthFailureCode.RATE_LIMITED, retryAfter(response));
        }
        if (status == 504) {
            return SingleAttempt.retryableFailure(
                    ModelConnectionHealthFailureCode.TIMEOUT, retryAfter(response));
        }
        if (status >= 500) {
            return SingleAttempt.retryableFailure(
                    ModelConnectionHealthFailureCode.PROVIDER_REJECTED, retryAfter(response));
        }
        if (status == 408) {
            return SingleAttempt.failure(ModelConnectionHealthFailureCode.TIMEOUT);
        }
        if (status == 401 || status == 403) {
            return SingleAttempt.failure(ModelConnectionHealthFailureCode.AUTHENTICATION_FAILED);
        }
        if (status >= 200 && status < 300) {
            return parseSuccess(response.body(), request);
        }
        return SingleAttempt.failure(ModelConnectionHealthFailureCode.PROVIDER_REJECTED);
    }

    private SingleAttempt parseSuccess(String body, EmbeddingRequest request) {
        try {
            JsonNode root = JSON.readTree(body);
            JsonNode data = root.get("data");
            if (data == null || !data.isArray() || data.size() != request.input().size()) {
                return SingleAttempt.failure(ModelConnectionHealthFailureCode.PROVIDER_REJECTED);
            }
            float[][] vectors = new float[request.input().size()][];
            for (JsonNode item : data) {
                JsonNode index = item.get("index");
                JsonNode embedding = item.get("embedding");
                if (index == null || !index.isNumber()
                        || embedding == null || !embedding.isArray()) {
                    return SingleAttempt.failure(
                            ModelConnectionHealthFailureCode.PROVIDER_REJECTED);
                }
                int position = index.asInt();
                if (position < 0 || position >= vectors.length || vectors[position] != null) {
                    return SingleAttempt.failure(
                            ModelConnectionHealthFailureCode.PROVIDER_REJECTED);
                }
                if (embedding.size() != request.dimensions()) {
                    return SingleAttempt.failure(
                            ModelConnectionHealthFailureCode.PROVIDER_REJECTED);
                }
                float[] vector = new float[embedding.size()];
                for (int i = 0; i < vector.length; i++) {
                    JsonNode component = embedding.get(i);
                    if (component == null || !component.isNumber()
                            || !Double.isFinite(component.asDouble())) {
                        return SingleAttempt.failure(
                                ModelConnectionHealthFailureCode.PROVIDER_REJECTED);
                    }
                    vector[i] = (float) component.asDouble();
                }
                vectors[position] = vector;
            }
            for (float[] vector : vectors) {
                if (vector == null) {
                    return SingleAttempt.failure(
                            ModelConnectionHealthFailureCode.PROVIDER_REJECTED);
                }
            }
            return SingleAttempt.delivered(List.of(vectors), parseUsage(root.get("usage")));
        } catch (RuntimeException | java.io.IOException malformed) {
            // A malformed success body is a provider contract violation, never a crash.
            return SingleAttempt.failure(ModelConnectionHealthFailureCode.PROVIDER_REJECTED);
        }
    }

    /**
     * Measured usage echo (S01 §3.2): {@code prompt_tokens}/{@code total_tokens} with no
     * output counter. Output tokens are structurally zero for embeddings, so the fact
     * always books input==total; missing counters mean unreported, never free.
     */
    private static ModelTokenUsage parseUsage(JsonNode usage) {
        if (usage == null || !usage.isObject()) {
            return ModelTokenUsage.unreported();
        }
        long prompt = nonNegative(usage.get("prompt_tokens"));
        return new ModelTokenUsage(prompt, 0, 0, prompt);
    }

    private static long nonNegative(JsonNode value) {
        if (value == null || !value.isNumber()) {
            return 0;
        }
        return Math.max(0, value.asLong());
    }

    private static Optional<Duration> retryAfter(HttpResponse<String> response) {
        return response.headers()
                .firstValue("Retry-After")
                .map(value -> {
                    try {
                        return Duration.ofSeconds(Math.max(0, Long.parseLong(value.strip())));
                    } catch (NumberFormatException notSeconds) {
                        return null;
                    }
                });
    }

    private static void sleep(Duration delay) {
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static URI embeddingsUri(ModelConnection connection) {
        String endpoint = connection.endpoint().value();
        String base = endpoint.endsWith("/") ? endpoint : endpoint + "/";
        return URI.create(base).resolve("embeddings");
    }

    private static Duration requirePositive(Duration value, String name) {
        Duration required = Objects.requireNonNull(value, name);
        if (required.isZero() || required.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return required;
    }

    /** Sanitized single-HTTP-attempt verdict before any retry bookkeeping. */
    private record SingleAttempt(
            boolean delivered,
            ModelConnectionHealthFailureCode failureCode,
            boolean retryable,
            Optional<Duration> retryAfter,
            List<float[]> vectors,
            ModelTokenUsage usage) {

        static SingleAttempt delivered(List<float[]> vectors, ModelTokenUsage usage) {
            return new SingleAttempt(true, null, false, Optional.empty(), vectors, usage);
        }

        static SingleAttempt failure(ModelConnectionHealthFailureCode failureCode) {
            return new SingleAttempt(false, failureCode, false, Optional.empty(),
                    List.of(), ModelTokenUsage.unreported());
        }

        static SingleAttempt retryableFailure(
                ModelConnectionHealthFailureCode failureCode, Optional<Duration> retryAfter) {
            return new SingleAttempt(false, failureCode, true,
                    Objects.requireNonNull(retryAfter, "retryAfter"),
                    List.of(), ModelTokenUsage.unreported());
        }
    }
}
