package io.crewscope.domain.coding;

import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.task.TaskFactHash;
import java.util.Objects;

/** Versioned immutable reference to a BuildProfile definition finalized in M4-D04. */
public record BuildProfileReference(String key, long version, TaskFactHash profileHash) {

    public static final String KEY_REGEX = "[a-z][a-z0-9._-]{0,127}";

    public BuildProfileReference {
        if (key == null || !key.matches(KEY_REGEX)) {
            throw new DomainValidationException(
                    "codingTargetSnapshot.buildProfile.key", "must match " + KEY_REGEX);
        }
        if (version < 1) {
            throw new DomainValidationException(
                    "codingTargetSnapshot.buildProfile.version", "must be positive");
        }
        profileHash = Objects.requireNonNull(profileHash, "profileHash");
    }
}
