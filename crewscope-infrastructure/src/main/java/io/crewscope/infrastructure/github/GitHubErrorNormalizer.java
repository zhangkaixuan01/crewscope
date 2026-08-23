package io.crewscope.infrastructure.github;

import io.crewscope.application.github.GitHubProviderErrorCode;
import io.crewscope.application.github.GitHubProviderException;
import java.net.http.HttpHeaders;
import java.util.Objects;

/** Converts GitHub transport facts into stable errors without retaining response bodies. */
final class GitHubErrorNormalizer {

    private GitHubErrorNormalizer() {}

    static GitHubProviderException normalize(int status, HttpHeaders headers) {
        Objects.requireNonNull(headers, "headers");
        if (status == 401) {
            return failure(GitHubProviderErrorCode.AUTHENTICATION_REQUIRED,
                    "GitHub authentication failed");
        }
        if (status == 403 && headers.firstValue("X-RateLimit-Remaining").orElse("").equals("0")) {
            return failure(GitHubProviderErrorCode.RATE_LIMITED,
                    "GitHub request is rate limited");
        }
        if (status == 403) {
            return failure(GitHubProviderErrorCode.PERMISSION_DENIED,
                    "GitHub permission is insufficient");
        }
        if (status == 404) {
            return failure(GitHubProviderErrorCode.RESOURCE_UNAVAILABLE,
                    "GitHub resource is unavailable");
        }
        if (status == 409) {
            return failure(GitHubProviderErrorCode.CONFLICT,
                    "GitHub resource has conflicting state");
        }
        if (status == 422) {
            return failure(GitHubProviderErrorCode.VALIDATION_FAILED,
                    "GitHub rejected the request shape");
        }
        if (status == 429) {
            return failure(GitHubProviderErrorCode.RATE_LIMITED,
                    "GitHub request is rate limited");
        }
        return failure(GitHubProviderErrorCode.PROVIDER_UNAVAILABLE,
                "GitHub provider is unavailable");
    }

    static GitHubProviderException transportFailure() {
        return failure(GitHubProviderErrorCode.PROVIDER_UNAVAILABLE,
                "GitHub provider is unavailable");
    }

    private static GitHubProviderException failure(
            GitHubProviderErrorCode code, String summary) {
        return new GitHubProviderException(code, summary);
    }
}
