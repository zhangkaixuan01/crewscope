package io.crewscope.infrastructure.workspace.git;

/** Full lower-case SHA-1 identity returned by {@code git write-tree}. */
public record GitTreeId(String value) {

    public GitTreeId {
        if (value == null || !value.matches("[0-9a-f]{40}")) {
            throw new IllegalArgumentException("Git tree ID must be a full lower-case SHA-1");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
