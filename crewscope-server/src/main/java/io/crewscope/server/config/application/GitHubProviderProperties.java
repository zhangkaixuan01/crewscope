package io.crewscope.server.config.application;

import java.net.URI;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Network and credential bounds for the GitHub read-side Provider adapter. */
@ConfigurationProperties(prefix = "crewscope.provider.github")
public class GitHubProviderProperties {

    private URI apiBaseUri = URI.create("https://api.github.com");
    private URI webBaseUri = URI.create("https://github.com");
    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration requestTimeout = Duration.ofSeconds(15);
    private Duration catalogTtl = Duration.ofMinutes(5);
    private Duration credentialHandleTtl = Duration.ofSeconds(30);
    private URI gitBaseUri = URI.create("https://github.com");
    private String mirrorRoot = "./var/crewscope/github-mirrors";
    private String askPassRoot = "./var/crewscope/github-credentials";
    private String requiredOwner = System.getProperty("user.name");
    private boolean allowLoopbackHttp;
    private Set<String> repositoryAllowlist = Set.of();
    private Set<String> allowedOwnerLogins = Set.of();
    private boolean allowPrivateRepositories = true;
    private boolean allowInternalRepositories = true;
    private boolean allowBroadUserOauth;

    public URI getApiBaseUri() {
        return apiBaseUri;
    }

    public void setApiBaseUri(URI apiBaseUri) {
        this.apiBaseUri = apiBaseUri;
    }

    public URI getWebBaseUri() {
        return webBaseUri;
    }

    public void setWebBaseUri(URI webBaseUri) {
        this.webBaseUri = webBaseUri;
    }

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

    public Duration getCatalogTtl() {
        return catalogTtl;
    }

    public void setCatalogTtl(Duration catalogTtl) {
        this.catalogTtl = catalogTtl;
    }

    public Duration getCredentialHandleTtl() {
        return credentialHandleTtl;
    }

    public void setCredentialHandleTtl(Duration credentialHandleTtl) {
        this.credentialHandleTtl = credentialHandleTtl;
    }

    public boolean isAllowLoopbackHttp() {
        return allowLoopbackHttp;
    }

    public URI getGitBaseUri() {
        return gitBaseUri;
    }

    public void setGitBaseUri(URI gitBaseUri) {
        this.gitBaseUri = gitBaseUri;
    }

    public String getMirrorRoot() {
        return mirrorRoot;
    }

    public void setMirrorRoot(String mirrorRoot) {
        this.mirrorRoot = mirrorRoot;
    }

    public String getAskPassRoot() {
        return askPassRoot;
    }

    public void setAskPassRoot(String askPassRoot) {
        this.askPassRoot = askPassRoot;
    }

    public String getRequiredOwner() {
        return requiredOwner;
    }

    public void setRequiredOwner(String requiredOwner) {
        this.requiredOwner = requiredOwner;
    }

    public void setAllowLoopbackHttp(boolean allowLoopbackHttp) {
        this.allowLoopbackHttp = allowLoopbackHttp;
    }

    public Set<String> getRepositoryAllowlist() {
        return repositoryAllowlist;
    }

    public void setRepositoryAllowlist(Set<String> repositoryAllowlist) {
        this.repositoryAllowlist = repositoryAllowlist;
    }

    public Set<String> getAllowedOwnerLogins() {
        return allowedOwnerLogins;
    }

    public void setAllowedOwnerLogins(Set<String> allowedOwnerLogins) {
        this.allowedOwnerLogins = allowedOwnerLogins;
    }

    public boolean isAllowPrivateRepositories() {
        return allowPrivateRepositories;
    }

    public void setAllowPrivateRepositories(boolean allowPrivateRepositories) {
        this.allowPrivateRepositories = allowPrivateRepositories;
    }

    public boolean isAllowInternalRepositories() {
        return allowInternalRepositories;
    }

    public void setAllowInternalRepositories(boolean allowInternalRepositories) {
        this.allowInternalRepositories = allowInternalRepositories;
    }

    public boolean isAllowBroadUserOauth() {
        return allowBroadUserOauth;
    }

    public void setAllowBroadUserOauth(boolean allowBroadUserOauth) {
        this.allowBroadUserOauth = allowBroadUserOauth;
    }

    public URI validatedApiBaseUri() {
        return Objects.requireNonNull(apiBaseUri, "crewscope.provider.github.api-base-uri");
    }

    public URI validatedWebBaseUri() {
        return Objects.requireNonNull(webBaseUri, "crewscope.provider.github.web-base-uri");
    }

    public Duration validatedConnectTimeout() {
        return positiveAtMost(connectTimeout, Duration.ofMinutes(1), "connect-timeout");
    }

    public Duration validatedRequestTimeout() {
        return positiveAtMost(requestTimeout, Duration.ofMinutes(2), "request-timeout");
    }

    public Duration validatedCatalogTtl() {
        return positiveAtMost(catalogTtl, Duration.ofHours(24), "catalog-ttl");
    }

    public Duration validatedCredentialHandleTtl() {
        return positiveAtMost(
                credentialHandleTtl, Duration.ofMinutes(5), "credential-handle-ttl");
    }

    public URI validatedGitBaseUri() {
        return Objects.requireNonNull(
                gitBaseUri, "crewscope.provider.github.git-base-uri");
    }

    public Path validatedMirrorRoot() {
        return path(mirrorRoot, "mirror-root");
    }

    public Path validatedAskPassRoot() {
        return path(askPassRoot, "ask-pass-root");
    }

    public String validatedRequiredOwner() {
        if (requiredOwner == null || requiredOwner.isBlank() || requiredOwner.indexOf('\0') >= 0) {
            throw new IllegalStateException(
                    "crewscope.provider.github.required-owner must be non-blank");
        }
        return requiredOwner.strip();
    }

    private static Duration positiveAtMost(
            Duration value, Duration maximum, String property) {
        Duration required = Objects.requireNonNull(
                value, "crewscope.provider.github." + property);
        if (required.isZero() || required.isNegative() || required.compareTo(maximum) > 0) {
            throw new IllegalStateException(
                    "crewscope.provider.github." + property
                            + " must be positive and at most " + maximum);
        }
        return required;
    }

    private static Path path(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "crewscope.provider.github." + property + " must be non-blank");
        }
        try {
            return Path.of(value).toAbsolutePath().normalize();
        } catch (InvalidPathException invalidPath) {
            throw new IllegalStateException(
                    "crewscope.provider.github." + property + " is invalid");
        }
    }
}
