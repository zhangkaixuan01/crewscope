package io.crewscope.application.provider;

import io.crewscope.domain.provider.ProviderBindingId;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable resolution outcome with stable ambiguity identifiers and no implicit fallback. */
public record ProviderBindingResolution(
        ProviderBindingResolutionStatus status,
        ProviderBindingResolutionLevel level,
        Optional<ProviderBindingCandidate> candidate,
        List<ProviderBindingId> ambiguousBindingIds) {

    public ProviderBindingResolution {
        status = Objects.requireNonNull(status, "status");
        level = Objects.requireNonNull(level, "level");
        candidate = Objects.requireNonNull(candidate, "candidate");
        ambiguousBindingIds = Objects.requireNonNull(
                        ambiguousBindingIds, "ambiguousBindingIds")
                .stream()
                .distinct()
                .sorted(Comparator.comparing(ProviderBindingId::toString))
                .toList();
        boolean valid = switch (status) {
            case RESOLVED -> candidate.isPresent() && ambiguousBindingIds.isEmpty();
            case NOT_FOUND -> candidate.isEmpty() && ambiguousBindingIds.isEmpty();
            case AMBIGUOUS -> candidate.isEmpty() && ambiguousBindingIds.size() >= 2;
        };
        if (!valid) {
            throw new IllegalArgumentException("invalid ProviderBindingResolution shape");
        }
    }

    public static ProviderBindingResolution resolved(
            ProviderBindingResolutionLevel level, ProviderBindingCandidate candidate) {
        return new ProviderBindingResolution(
                ProviderBindingResolutionStatus.RESOLVED,
                level,
                Optional.of(Objects.requireNonNull(candidate, "candidate")),
                List.of());
    }

    public static ProviderBindingResolution notFound(ProviderBindingResolutionLevel level) {
        return new ProviderBindingResolution(
                ProviderBindingResolutionStatus.NOT_FOUND,
                level,
                Optional.empty(),
                List.of());
    }

    public static ProviderBindingResolution ambiguous(
            ProviderBindingResolutionLevel level, List<ProviderBindingId> bindingIds) {
        return new ProviderBindingResolution(
                ProviderBindingResolutionStatus.AMBIGUOUS,
                level,
                Optional.empty(),
                bindingIds);
    }

    public boolean isResolved() {
        return status == ProviderBindingResolutionStatus.RESOLVED;
    }
}
