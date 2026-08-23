package io.crewscope.infrastructure.github;

import io.crewscope.application.github.GitHubPushErrorCode;
import io.crewscope.application.github.GitHubPushException;
import java.net.URI;
import java.util.Objects;

/** Builds a credential-free canonical HTTPS Remote from trusted GitHub repository facts. */
final class GitHubRemoteUriFactory {

    private final URI baseUri;

    GitHubRemoteUriFactory(URI baseUri) {
        URI required = Objects.requireNonNull(baseUri, "baseUri");
        if (!"https".equalsIgnoreCase(required.getScheme())
                || required.getHost() == null
                || required.getUserInfo() != null
                || required.getPort() != -1
                || required.getRawQuery() != null
                || required.getRawFragment() != null
                || !(required.getPath().isEmpty() || "/".equals(required.getPath()))) {
            throw new IllegalArgumentException(
                    "GitHub Git base URI must be an origin-only HTTPS URI");
        }
        this.baseUri = URI.create("https://" + required.getHost().toLowerCase(java.util.Locale.ROOT) + "/");
    }

    URI create(String repositoryFullName) {
        String value = Objects.requireNonNull(repositoryFullName, "repositoryFullName");
        if (!value.matches("[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})/[A-Za-z0-9_.-]{1,100}")) {
            throw new GitHubPushException(
                    GitHubPushErrorCode.AUTHORITY_STALE,
                    "GitHub repository name is invalid");
        }
        return baseUri.resolve(value + ".git");
    }
}
