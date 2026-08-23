package io.crewscope.infrastructure.model;

import io.crewscope.application.model.ModelProviderHealthProbe;
import io.crewscope.application.model.ProviderCredentialHandle;
import io.crewscope.domain.model.ModelConnection;
import io.crewscope.domain.model.ModelConnectionHealthFailureCode;
import io.crewscope.domain.model.ModelProviderDefinition;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

/** OpenAI-compatible GET /models probe with status-only processing and safe failure mapping. */
public final class OpenAiCompatibleModelProviderHealthProbe implements ModelProviderHealthProbe {

    private final HttpClient httpClient;
    private final Duration requestTimeout;

    public OpenAiCompatibleModelProviderHealthProbe(
            HttpClient httpClient, Duration requestTimeout) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.requestTimeout = requirePositive(requestTimeout);
    }

    @Override
    public ProbeResult probe(
            ModelProviderDefinition provider,
            ModelConnection connection,
            ProviderCredentialHandle credentialHandle) {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(credentialHandle, "credentialHandle");
        return credentialHandle.useSecret(secret -> send(connection, secret));
    }

    private ProbeResult send(ModelConnection connection, byte[] secret) {
        String bearerToken = new String(secret, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(modelsUri(connection))
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + bearerToken)
                .GET()
                .build();
        try {
            HttpResponse<Void> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.discarding());
            return mapStatus(response.statusCode());
        } catch (java.net.http.HttpTimeoutException exception) {
            return ProbeResult.failed(ModelConnectionHealthFailureCode.TIMEOUT);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return ProbeResult.failed(ModelConnectionHealthFailureCode.TIMEOUT);
        } catch (IOException exception) {
            return ProbeResult.failed(ModelConnectionHealthFailureCode.ENDPOINT_UNREACHABLE);
        } catch (RuntimeException exception) {
            return ProbeResult.failed(ModelConnectionHealthFailureCode.PROVIDER_REJECTED);
        }
    }

    private static ProbeResult mapStatus(int statusCode) {
        if (statusCode >= 200 && statusCode < 300) {
            return ProbeResult.success();
        }
        if (statusCode == 401 || statusCode == 403) {
            return ProbeResult.failed(ModelConnectionHealthFailureCode.AUTHENTICATION_FAILED);
        }
        if (statusCode == 408 || statusCode == 504) {
            return ProbeResult.failed(ModelConnectionHealthFailureCode.TIMEOUT);
        }
        if (statusCode == 429) {
            return ProbeResult.failed(ModelConnectionHealthFailureCode.RATE_LIMITED);
        }
        return ProbeResult.failed(ModelConnectionHealthFailureCode.PROVIDER_REJECTED);
    }

    private static URI modelsUri(ModelConnection connection) {
        String endpoint = connection.endpoint().value();
        String base = endpoint.endsWith("/") ? endpoint : endpoint + "/";
        return URI.create(base).resolve("models");
    }

    private static Duration requirePositive(Duration value) {
        Duration required = Objects.requireNonNull(value, "requestTimeout");
        if (required.isZero() || required.isNegative()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
        return required;
    }
}
