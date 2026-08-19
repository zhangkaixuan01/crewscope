package io.crewscope.infrastructure.workspace.repository;

import io.crewscope.domain.coding.AllowedPathSet;
import io.crewscope.domain.coding.CodingTargetAllowedPaths;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/** Validates repository-relative paths without exposing the canonical host Worktree location. */
final class RepositoryInspectionPathGuard {

    private static final LinkOption[] NO_FOLLOW_LINKS = {LinkOption.NOFOLLOW_LINKS};

    private final Path worktreeRoot;
    private final String containerRoot;
    private final AllowedPathSet allowedPaths;
    private final int maximumPatternLength;

    RepositoryInspectionPathGuard(
            Path worktreeRoot,
            String containerRoot,
            AllowedPathSet allowedPaths,
            int maximumPatternLength) {
        this.worktreeRoot = Objects.requireNonNull(worktreeRoot, "worktreeRoot")
                .toAbsolutePath()
                .normalize();
        this.containerRoot = requireContainerRoot(containerRoot);
        this.allowedPaths = Objects.requireNonNull(allowedPaths, "allowedPaths");
        this.maximumPatternLength = maximumPatternLength;
    }

    String requirePath(String supplied) {
        String relative = canonical(supplied);
        if (!allowedPaths.allows(relative)) {
            throw failure(
                    RepositoryInspectionError.PATH_NOT_ALLOWED,
                    "Repository path is outside the Workspace Policy allowed roots");
        }
        requireNonSensitive(relative);
        requireNoSymbolicLink(relative);
        return relative;
    }

    String toContainerPath(String relative) {
        return ".".equals(relative) ? containerRoot : containerRoot + "/" + relative;
    }

    /** Resolves a previously validated repository path without exposing it outside this package. */
    Path toHostPath(String relative) {
        return ".".equals(relative) ? worktreeRoot : worktreeRoot.resolve(relative);
    }

    String requireReturnedPath(String sandboxPath) {
        if (sandboxPath == null) {
            throw invalidReturnedPath();
        }
        String prefix = containerRoot + "/";
        String relative;
        if (sandboxPath.equals(containerRoot)) {
            relative = ".";
        } else if (sandboxPath.startsWith(prefix)) {
            relative = sandboxPath.substring(prefix.length());
        } else {
            throw invalidReturnedPath();
        }
        return requirePath(relative);
    }

    String requireLiteralPattern(String pattern) {
        String value = requirePattern(pattern, "Search pattern");
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw invalidRequest("Search pattern must be one line");
        }
        return value;
    }

    String requireGlobPattern(String pattern) {
        String value = requirePattern(pattern, "Glob pattern");
        String filenamePattern = value.startsWith("**/") ? value.substring(3) : value;
        if (filenamePattern.isBlank()
                || filenamePattern.indexOf('/') >= 0
                || filenamePattern.indexOf('\\') >= 0) {
            throw invalidRequest(
                    "Glob pattern must target file names and may only use an optional **/ prefix");
        }
        return value;
    }

    private String requirePattern(String pattern, String name) {
        if (pattern == null
                || pattern.isBlank()
                || pattern.length() > maximumPatternLength
                || pattern.chars().anyMatch(character -> character == 0 || character < 0x20)) {
            throw invalidRequest(name + " is blank, too long or contains control characters");
        }
        return pattern;
    }

    private String canonical(String supplied) {
        try {
            CodingTargetAllowedPaths normalized = CodingTargetAllowedPaths.of(supplied);
            if (normalized.values().size() != 1
                    || !normalized.values().get(0).equals(supplied)) {
                throw invalidPath();
            }
            return supplied;
        } catch (RepositoryInspectionException failure) {
            throw failure;
        } catch (RuntimeException invalid) {
            throw invalidPath();
        }
    }

    private void requireNonSensitive(String relative) {
        if (".".equals(relative)) {
            return;
        }
        for (String component : relative.split("/")) {
            String lower = component.toLowerCase(Locale.ROOT);
            if (isSensitiveName(lower)) {
                throw failure(
                        RepositoryInspectionError.SENSITIVE_PATH,
                        "Sensitive repository paths are unavailable to inspection tools");
            }
        }
    }

    private void requireNoSymbolicLink(String relative) {
        if (".".equals(relative)) {
            return;
        }
        Path current = worktreeRoot;
        for (String component : relative.split("/")) {
            current = current.resolve(component);
            if (Files.exists(current, NO_FOLLOW_LINKS) && Files.isSymbolicLink(current)) {
                throw failure(
                        RepositoryInspectionError.SYMBOLIC_LINK,
                        "Symbolic links are unavailable to repository inspection tools");
            }
        }
    }

    private static boolean isSensitiveName(String name) {
        return name.equals(".git")
                || name.equals(".env")
                || name.startsWith(".env.")
                || name.equals(".ssh")
                || name.equals(".aws")
                || name.equals(".gnupg")
                || name.equals(".docker")
                || name.equals(".npmrc")
                || name.equals(".pypirc")
                || name.equals("id_rsa")
                || name.equals("id_ed25519")
                || name.equals("credentials")
                || name.equals("credentials.json")
                || name.endsWith(".pem")
                || name.endsWith(".key")
                || name.endsWith(".p12")
                || name.endsWith(".pfx")
                || name.endsWith(".jks")
                || name.endsWith(".keystore");
    }

    private static String requireContainerRoot(String value) {
        String root = Objects.requireNonNull(value, "containerRoot");
        if (!root.startsWith("/") || root.endsWith("/") || root.contains("//")) {
            throw new IllegalArgumentException("Inspection container root must be canonical");
        }
        return root;
    }

    private static RepositoryInspectionException invalidPath() {
        return failure(
                RepositoryInspectionError.INVALID_PATH,
                "Repository path must be canonical and repository-relative");
    }

    private static RepositoryInspectionException invalidReturnedPath() {
        return failure(
                RepositoryInspectionError.FILESYSTEM_FAILED,
                "AgentScope filesystem returned a path outside the repository mount");
    }

    private static RepositoryInspectionException invalidRequest(String message) {
        return failure(RepositoryInspectionError.INVALID_REQUEST, message);
    }

    private static RepositoryInspectionException failure(
            RepositoryInspectionError error, String message) {
        return new RepositoryInspectionException(error, message);
    }
}
