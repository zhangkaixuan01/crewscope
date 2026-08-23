package io.crewscope.application.github;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Organization policy intersected with ConnectionGrant resources during catalog discovery. */
public record GitHubRepositoryPolicy(
        Set<String> repositoryAllowlist,
        Set<String> allowedOwnerLogins,
        boolean allowPrivateRepositories,
        boolean allowInternalRepositories,
        boolean allowBroadUserOauth) {

    public GitHubRepositoryPolicy {
        repositoryAllowlist = normalize(repositoryAllowlist, "repositoryAllowlist");
        allowedOwnerLogins = normalize(allowedOwnerLogins, "allowedOwnerLogins");
    }

    public String allowlistHash() {
        return GitHubHash.canonicalCollection(repositoryAllowlist);
    }

    public boolean permits(String fullName, String ownerLogin, GitHubRepositoryVisibility visibility) {
        String repository = normalizeOne(fullName);
        String owner = normalizeOne(ownerLogin);
        if (!repositoryAllowlist.contains(repository)) {
            return false;
        }
        if (!allowedOwnerLogins.isEmpty() && !allowedOwnerLogins.contains(owner)) {
            return false;
        }
        return switch (Objects.requireNonNull(visibility, "visibility")) {
            case PUBLIC -> true;
            case PRIVATE -> allowPrivateRepositories;
            case INTERNAL -> allowInternalRepositories;
        };
    }

    private static Set<String> normalize(Set<String> values, String field) {
        Objects.requireNonNull(values, field);
        TreeSet<String> result = new TreeSet<>();
        for (String value : values) {
            result.add(normalizeOne(value));
        }
        return Set.copyOf(result);
    }

    private static String normalizeOne(String value) {
        String normalized = GitHubHash.requireText(value).toLowerCase(Locale.ROOT);
        if (normalized.length() > 511) {
            throw new IllegalArgumentException("GitHub policy resource exceeds its maximum length");
        }
        return normalized;
    }
}
