package io.crewscope.application.github;

import java.util.EnumSet;
import java.util.Set;

/** Normalized GitHub permissions used by CrewScope delivery policy. */
public enum GitHubPermission {
    REPOSITORY_METADATA_READ,
    CONTENTS_READ,
    CONTENTS_WRITE,
    PULL_REQUESTS_WRITE,
    ADMINISTRATION,
    ACTIONS,
    SECRETS,
    MEMBERS,
    WEBHOOKS_WRITE;

    public static Set<GitHubPermission> minimumDraftDelivery() {
        return Set.copyOf(EnumSet.of(
                REPOSITORY_METADATA_READ,
                CONTENTS_READ,
                CONTENTS_WRITE,
                PULL_REQUESTS_WRITE));
    }

    public static Set<GitHubPermission> forbiddenElevatedPermissions() {
        return Set.copyOf(EnumSet.of(
                ADMINISTRATION, ACTIONS, SECRETS, MEMBERS, WEBHOOKS_WRITE));
    }
}
