package io.crewscope.server.config.application;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Network and cache ceilings for connection-scoped dynamic AgentScope models. */
@ConfigurationProperties(prefix = "crewscope.model.dynamic")
public class DynamicAgentScopeModelProperties {

  private Duration cacheTtl = Duration.ofMinutes(5);
  private int maximumCacheEntries = 256;
  private Duration requestTimeout = Duration.ofMinutes(5);
  private Duration retryInitialBackoff = Duration.ofSeconds(2);
  private Duration retryMaximumBackoff = Duration.ofSeconds(30);

  public Duration getCacheTtl() {
    return cacheTtl;
  }

  public void setCacheTtl(Duration cacheTtl) {
    this.cacheTtl = cacheTtl;
  }

  public int getMaximumCacheEntries() {
    return maximumCacheEntries;
  }

  public void setMaximumCacheEntries(int maximumCacheEntries) {
    this.maximumCacheEntries = maximumCacheEntries;
  }

  public Duration getRequestTimeout() {
    return requestTimeout;
  }

  public void setRequestTimeout(Duration requestTimeout) {
    this.requestTimeout = requestTimeout;
  }

  public Duration getRetryInitialBackoff() {
    return retryInitialBackoff;
  }

  public void setRetryInitialBackoff(Duration retryInitialBackoff) {
    this.retryInitialBackoff = retryInitialBackoff;
  }

  public Duration getRetryMaximumBackoff() {
    return retryMaximumBackoff;
  }

  public void setRetryMaximumBackoff(Duration retryMaximumBackoff) {
    this.retryMaximumBackoff = retryMaximumBackoff;
  }

  public Duration validatedCacheTtl() {
    return positiveAtMost(cacheTtl, Duration.ofHours(1), "cache-ttl");
  }

  public int validatedMaximumCacheEntries() {
    if (maximumCacheEntries < 1 || maximumCacheEntries > 10_000) {
      throw new IllegalStateException(
          "crewscope.model.dynamic.maximum-cache-entries must be between 1 and 10000");
    }
    return maximumCacheEntries;
  }

  public Duration validatedRequestTimeout() {
    return positiveAtMost(requestTimeout, Duration.ofMinutes(30), "request-timeout");
  }

  public Duration validatedRetryInitialBackoff() {
    return positiveAtMost(
        retryInitialBackoff, Duration.ofMinutes(1), "retry-initial-backoff");
  }

  public Duration validatedRetryMaximumBackoff() {
    Duration value = positiveAtMost(
        retryMaximumBackoff, Duration.ofMinutes(5), "retry-maximum-backoff");
    if (value.compareTo(validatedRetryInitialBackoff()) < 0) {
      throw new IllegalStateException(
          "crewscope.model.dynamic.retry-maximum-backoff must not be shorter than the initial backoff");
    }
    return value;
  }

  private static Duration positiveAtMost(Duration value, Duration maximum, String name) {
    Duration required = Objects.requireNonNull(value, "crewscope.model.dynamic." + name);
    if (required.isZero() || required.isNegative() || required.compareTo(maximum) > 0) {
      throw new IllegalStateException(
          "crewscope.model.dynamic." + name + " must be positive and at most " + maximum);
    }
    return required;
  }
}
