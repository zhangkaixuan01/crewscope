package io.crewscope.domain.coding;

/** Build entrypoint family whose executable is fixed by the platform. */
public enum BuildTool {
    MAVEN("mvn"),
    MAVEN_WRAPPER("./mvnw"),
    GRADLE_WRAPPER("./gradlew"),
    PROJECT_SCRIPT("./scripts/");

    private final String executable;

    BuildTool(String executable) {
        this.executable = executable;
    }

    boolean accepts(String candidate) {
        return this == PROJECT_SCRIPT
                ? candidate.startsWith(executable) && candidate.length() > executable.length()
                : executable.equals(candidate);
    }
}
