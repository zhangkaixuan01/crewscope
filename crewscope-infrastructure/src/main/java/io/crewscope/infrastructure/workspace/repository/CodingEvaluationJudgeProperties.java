package io.crewscope.infrastructure.workspace.repository;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Opt-in source root for hidden Coding benchmark Judge tests. */
@ConfigurationProperties("crewscope.coding.evaluation")
public class CodingEvaluationJudgeProperties {

    private String judgeTestsRoot = "";
    private String repositoryKey = "coding-evaluation";

    public String getJudgeTestsRoot() {
        return judgeTestsRoot;
    }

    public void setJudgeTestsRoot(String judgeTestsRoot) {
        this.judgeTestsRoot = judgeTestsRoot;
    }

    public String getRepositoryKey() {
        return repositoryKey;
    }

    public void setRepositoryKey(String repositoryKey) {
        this.repositoryKey = repositoryKey;
    }

    String requiredRepositoryKey() {
        String value = repositoryKey == null ? "" : repositoryKey.strip();
        if (!value.matches("[a-z0-9][a-z0-9-]{0,62}")) {
            throw new IllegalArgumentException(
                    "Coding evaluation Judge repository key is invalid");
        }
        return value;
    }

    Optional<Path> judgeTestsRootPath() {
        if (judgeTestsRoot == null || judgeTestsRoot.isBlank()) {
            return Optional.empty();
        }
        Path configured = Path.of(judgeTestsRoot.strip());
        if (!configured.isAbsolute()) {
            throw new IllegalArgumentException(
                    "Coding evaluation Judge tests root must be absolute");
        }
        try {
            if (Files.isSymbolicLink(configured)
                    || !Files.isDirectory(configured, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException(
                        "Coding evaluation Judge tests root must be a physical directory");
            }
            return Optional.of(configured.toRealPath(LinkOption.NOFOLLOW_LINKS));
        } catch (java.io.IOException failure) {
            throw new IllegalArgumentException(
                    "Coding evaluation Judge tests root could not be verified", failure);
        }
    }
}
