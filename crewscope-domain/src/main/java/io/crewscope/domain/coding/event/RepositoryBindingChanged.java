package io.crewscope.domain.coding.event;

import io.crewscope.domain.coding.RepositoryBinding;
import io.crewscope.domain.coding.RepositoryBindingStatus;
import io.crewscope.domain.coding.RepositoryKind;
import io.crewscope.domain.shared.DomainEvent;
import java.util.Objects;

/** Version 1 path-free business payload emitted for RepositoryBinding lifecycle changes. */
public record RepositoryBindingChanged(
        RepositoryKind kind,
        String repositoryKey,
        String defaultBranch,
        RepositoryBindingStatus status)
        implements DomainEvent {

    public RepositoryBindingChanged {
        kind = Objects.requireNonNull(kind, "kind");
        if (repositoryKey == null || repositoryKey.isBlank()) {
            throw new IllegalArgumentException("repositoryKey must not be blank");
        }
        if (defaultBranch == null || defaultBranch.isBlank()) {
            throw new IllegalArgumentException("defaultBranch must not be blank");
        }
        status = Objects.requireNonNull(status, "status");
    }

    public static RepositoryBindingChanged from(RepositoryBinding binding) {
        RepositoryBinding source = Objects.requireNonNull(binding, "binding");
        return new RepositoryBindingChanged(
                source.kind(),
                source.repositoryKey().value(),
                source.defaultBranch().value(),
                source.status());
    }
}
