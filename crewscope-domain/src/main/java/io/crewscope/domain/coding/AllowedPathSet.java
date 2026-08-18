package io.crewscope.domain.coding;

import java.util.List;
import java.util.Objects;

/** Effective canonical repository-relative paths available to one Coding execution. */
public record AllowedPathSet(List<String> values) {

    public AllowedPathSet {
        values = new CodingTargetAllowedPaths(values).values();
    }

    public static AllowedPathSet of(String... values) {
        return new AllowedPathSet(List.of(values));
    }

    public static AllowedPathSet from(CodingTargetAllowedPaths paths) {
        return new AllowedPathSet(Objects.requireNonNull(paths, "paths").values());
    }

    public boolean allows(String path) {
        return asTargetPaths().allows(path);
    }

    /** Returns true when every root in {@code candidate} is within this set. */
    public boolean containsAll(AllowedPathSet candidate) {
        return asTargetPaths().containsAll(Objects.requireNonNull(candidate, "candidate").asTargetPaths());
    }

    private CodingTargetAllowedPaths asTargetPaths() {
        return new CodingTargetAllowedPaths(values);
    }
}
