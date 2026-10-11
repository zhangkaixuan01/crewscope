package io.crewscope.application.github;

import io.crewscope.domain.provider.ProviderAccessScope;
import io.crewscope.domain.provider.ProviderResourceScope;
import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Static encoding between GitHub repository ids and Provider resource keys. */
final class GitHubRepositoryResources {

    private static final String REPOSITORY_RESOURCE_PREFIX = "github:repository:";

    private GitHubRepositoryResources() {}

    static Set<String> repositoryAllowlist(ProviderResourceScope resources) {
        if (resources.unrestricted()) {
            // Discovery grants intentionally do not expose a delivery
            // allowlist.  A concrete ProviderBinding is required before
            // import or delivery can use the connection.
            return Set.of();
        }
        Set<String> result = resources.resources().stream()
                .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(REPOSITORY_RESOURCE_PREFIX))
                .map(value -> value.substring(REPOSITORY_RESOURCE_PREFIX.length()))
                .collect(Collectors.toSet());
        if (result.size() != resources.resources().size() || result.isEmpty()) {
            throw new DomainValidationException(
                    "githubConnection.repositoryAllowlist",
                    "contains an invalid GitHub Repository resource");
        }
        return Set.copyOf(result);
    }

    static ProviderAccessScope access(Set<String> repositories) {
        GitHubRepositoryPolicy normalized = new GitHubRepositoryPolicy(
                repositories, Set.of(), true, true, false);
        String[] resources = normalized.repositoryAllowlist().stream()
                .map(value -> REPOSITORY_RESOURCE_PREFIX + value)
                .toArray(String[]::new);
        return new ProviderAccessScope(
                GitHubConnectionApplicationService.DELIVERY_CAPABILITIES,
                repositories.isEmpty()
                        ? ProviderResourceScope.allResources()
                        : ProviderResourceScope.of(resources));
    }
}
