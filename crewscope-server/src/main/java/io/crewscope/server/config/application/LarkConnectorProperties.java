package io.crewscope.server.config.application;

import io.crewscope.integration.provider.collaboration.LarkEndpointPolicy;
import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Bounded network, credential and tenant-token settings for the Lark Connector. */
@ConfigurationProperties(prefix = "crewscope.provider.lark")
public class LarkConnectorProperties {

    private URI baseUri = LarkEndpointPolicy.PRODUCTION_ORIGIN;
    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration requestTimeout = Duration.ofSeconds(15);
    private Duration credentialHandleTtl = Duration.ofSeconds(30);
    private Duration tokenExpirySafetyMargin = Duration.ofSeconds(60);
    private Duration memberConfirmationWindow = Duration.ofMinutes(10);
    private int maximumCachedTokens = 1_024;
    private int maximumResponseBytes = 1_048_576;
    private boolean allowLoopbackHttp;

    public URI getBaseUri() { return baseUri; }
    public void setBaseUri(URI value) { baseUri = value; }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration value) { connectTimeout = value; }
    public Duration getRequestTimeout() { return requestTimeout; }
    public void setRequestTimeout(Duration value) { requestTimeout = value; }
    public Duration getCredentialHandleTtl() { return credentialHandleTtl; }
    public void setCredentialHandleTtl(Duration value) { credentialHandleTtl = value; }
    public Duration getTokenExpirySafetyMargin() { return tokenExpirySafetyMargin; }
    public void setTokenExpirySafetyMargin(Duration value) { tokenExpirySafetyMargin = value; }
    public Duration getMemberConfirmationWindow() { return memberConfirmationWindow; }
    public void setMemberConfirmationWindow(Duration value) { memberConfirmationWindow = value; }
    public int getMaximumCachedTokens() { return maximumCachedTokens; }
    public void setMaximumCachedTokens(int value) { maximumCachedTokens = value; }
    public int getMaximumResponseBytes() { return maximumResponseBytes; }
    public void setMaximumResponseBytes(int value) { maximumResponseBytes = value; }
    public boolean isAllowLoopbackHttp() { return allowLoopbackHttp; }
    public void setAllowLoopbackHttp(boolean value) { allowLoopbackHttp = value; }

    public URI validatedBaseUri() {
        return LarkEndpointPolicy.requireAllowed(
                Objects.requireNonNull(baseUri, "crewscope.provider.lark.base-uri"),
                allowLoopbackHttp);
    }

    public Duration validatedConnectTimeout() {
        return duration(connectTimeout, Duration.ofMillis(100), Duration.ofMinutes(1),
                "connect-timeout");
    }

    public Duration validatedRequestTimeout() {
        return duration(requestTimeout, Duration.ofMillis(100), Duration.ofMinutes(2),
                "request-timeout");
    }

    public Duration validatedCredentialHandleTtl() {
        return duration(credentialHandleTtl, Duration.ofMillis(1), Duration.ofMinutes(5),
                "credential-handle-ttl");
    }

    public Duration validatedTokenExpirySafetyMargin() {
        return duration(tokenExpirySafetyMargin, Duration.ofSeconds(60), Duration.ofMinutes(10),
                "token-expiry-safety-margin");
    }

    public Duration validatedMemberConfirmationWindow() {
        return duration(memberConfirmationWindow, Duration.ofSeconds(1), Duration.ofMinutes(15),
                "member-confirmation-window");
    }

    public int validatedMaximumCachedTokens() {
        if (maximumCachedTokens < 1 || maximumCachedTokens > 10_000) {
            throw new IllegalStateException(
                    "crewscope.provider.lark.maximum-cached-tokens must be between 1 and 10000");
        }
        return maximumCachedTokens;
    }

    public int validatedMaximumResponseBytes() {
        if (maximumResponseBytes < 1_024 || maximumResponseBytes > 1_048_576) {
            throw new IllegalStateException(
                    "crewscope.provider.lark.maximum-response-bytes must be between 1024 and 1048576");
        }
        return maximumResponseBytes;
    }

    private static Duration duration(
            Duration value, Duration minimum, Duration maximum, String property) {
        Duration required = Objects.requireNonNull(
                value, "crewscope.provider.lark." + property);
        if (required.compareTo(minimum) < 0 || required.compareTo(maximum) > 0) {
            throw new IllegalStateException(
                    "crewscope.provider.lark." + property + " is outside its supported range");
        }
        return required;
    }
}
