package io.crewscope.domain.policy;

import java.util.Objects;

/** Immutable identity and version of the PolicyPack used for one policy decision. */
public record PolicyPackReference(PolicyPackId id, long version) {

    public PolicyPackReference {
        id = Objects.requireNonNull(id, "id");
        if (version < 0) {
            throw new IllegalArgumentException("PolicyPack version must not be negative");
        }
    }
}
