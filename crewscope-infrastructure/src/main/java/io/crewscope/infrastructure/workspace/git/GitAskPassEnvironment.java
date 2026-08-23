package io.crewscope.infrastructure.workspace.git;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/** Path-only action environment for one Git HTTPS credential window. */
public record GitAskPassEnvironment(Path askPassProgram, Path secretFile) {

    public GitAskPassEnvironment {
        askPassProgram = absolute(askPassProgram, "askPassProgram");
        secretFile = absolute(secretFile, "secretFile");
    }

    Map<String, String> variables() {
        return Map.of(
                "GIT_ASKPASS", askPassProgram.toString(),
                "GIT_ASKPASS_REQUIRE", "force",
                "CREWSCOPE_GITHUB_TOKEN_FILE", secretFile.toString());
    }

    private static Path absolute(Path path, String field) {
        Path value = Objects.requireNonNull(path, field).toAbsolutePath().normalize();
        if (!value.isAbsolute()) {
            throw new IllegalArgumentException(field + " must be absolute");
        }
        return value;
    }
}
