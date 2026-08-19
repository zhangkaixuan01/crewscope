package io.crewscope.infrastructure.workspace.git;

/** Bounded platform-authored message supplied to {@code git commit-tree} over standard input. */
public record GitCommitMessage(String value) {

    public static final int MAXIMUM_LENGTH = 4_096;

    public GitCommitMessage {
        if (value == null
                || value.isBlank()
                || value.length() > MAXIMUM_LENGTH
                || value.indexOf('\0') >= 0
                || value.chars().anyMatch(character -> character < 0x20
                        && character != '\n'
                        && character != '\r'
                        && character != '\t')) {
            throw new IllegalArgumentException("Git commit message must be non-blank and bounded");
        }
    }
}
