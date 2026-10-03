package io.crewscope.server.config.application;

import io.crewscope.application.embedding.EmbeddingClient;
import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Network and retry bounds for the embeddings transport (M10-I01a, S01 §3.2). */
@ConfigurationProperties(prefix = "crewscope.model.embedding")
public class ModelEmbeddingProperties {

    private static final int MAXIMUM_ATTEMPTS = 5;

    private Duration connectTimeout = Duration.ofSeconds(3);
    private Duration requestTimeout = Duration.ofSeconds(30);
    private int retryMaxAttempts = 3;
    private Duration retryBaseDelay = Duration.ofSeconds(2);

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    public int getRetryMaxAttempts() {
        return retryMaxAttempts;
    }

    public void setRetryMaxAttempts(int retryMaxAttempts) {
        this.retryMaxAttempts = retryMaxAttempts;
    }

    public Duration getRetryBaseDelay() {
        return retryBaseDelay;
    }

    public void setRetryBaseDelay(Duration retryBaseDelay) {
        this.retryBaseDelay = retryBaseDelay;
    }

    public Duration validatedConnectTimeout() {
        return requirePositive(
                connectTimeout, "crewscope.model.embedding.connect-timeout");
    }

    public Duration validatedRequestTimeout() {
        Duration required = requirePositive(
                requestTimeout, "crewscope.model.embedding.request-timeout");
        if (required.compareTo(EmbeddingClient.MINIMUM_REQUEST_TIMEOUT) < 0) {
            throw new IllegalStateException(
                    "crewscope.model.embedding.request-timeout must be at least "
                            + EmbeddingClient.MINIMUM_REQUEST_TIMEOUT);
        }
        return required;
    }

    public int validatedRetryMaxAttempts() {
        if (retryMaxAttempts < 1 || retryMaxAttempts > MAXIMUM_ATTEMPTS) {
            throw new IllegalStateException(
                    "crewscope.model.embedding.retry-max-attempts must be between 1 and "
                            + MAXIMUM_ATTEMPTS);
        }
        return retryMaxAttempts;
    }

    public Duration validatedRetryBaseDelay() {
        return requirePositive(
                retryBaseDelay, "crewscope.model.embedding.retry-base-delay");
    }

    private static Duration requirePositive(Duration value, String propertyName) {
        Duration required = Objects.requireNonNull(value, propertyName);
        if (required.isZero() || required.isNegative()) {
            throw new IllegalStateException(propertyName + " must be positive");
        }
        return required;
    }
}
