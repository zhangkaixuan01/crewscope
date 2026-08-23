package io.crewscope.infrastructure.github;

import io.crewscope.application.github.GitHubPushErrorCode;
import io.crewscope.application.github.GitHubPushException;
import io.crewscope.infrastructure.workspace.git.GitAskPassEnvironment;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;

/** Owner-only one-action AskPass files; neither argv nor environment contains the token value. */
final class GitAskPassSession implements AutoCloseable {

    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> SECRET_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE);
    private static final Set<PosixFilePermission> PROGRAM_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> PROGRAM_CREATE_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE);
    private static final String PROGRAM = """
            #!/bin/sh
            case "$1" in
              *Username*) printf '%s\\n' 'x-access-token' ;;
              *Password*) /bin/cat "$CREWSCOPE_GITHUB_TOKEN_FILE" ;;
              *) exit 1 ;;
            esac
            """;

    private final Path directory;
    private final Path program;
    private final Path secretFile;
    private boolean closed;

    private GitAskPassSession(Path directory, Path program, Path secretFile) {
        this.directory = directory;
        this.program = program;
        this.secretFile = secretFile;
    }

    static GitAskPassSession open(Path configuredRoot, byte[] secret) {
        Path root = initializeRoot(configuredRoot);
        byte[] requiredSecret = Objects.requireNonNull(secret, "secret");
        if (!validSecret(requiredSecret)) {
            throw new GitHubPushException(
                    GitHubPushErrorCode.AUTHORITY_STALE,
                    "GitHub credential is unavailable");
        }
        Path directory = null;
        try {
            directory = Files.createTempDirectory(
                    root,
                    "action-",
                    PosixFilePermissions.asFileAttribute(DIRECTORY_PERMISSIONS));
            Path program = Files.createFile(
                    directory.resolve("askpass"),
                    PosixFilePermissions.asFileAttribute(PROGRAM_CREATE_PERMISSIONS));
            Files.writeString(
                    program,
                    PROGRAM,
                    java.nio.charset.StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.WRITE);
            Files.setPosixFilePermissions(program, PROGRAM_PERMISSIONS);
            Path secretFile = Files.createFile(
                    directory.resolve("secret"),
                    PosixFilePermissions.asFileAttribute(SECRET_PERMISSIONS));
            Files.write(
                    secretFile,
                    requiredSecret,
                    java.nio.file.StandardOpenOption.WRITE);
            return new GitAskPassSession(directory, program, secretFile);
        } catch (IOException | UnsupportedOperationException failure) {
            cleanup(directory);
            throw new GitHubPushException(
                    GitHubPushErrorCode.AUTHORITY_STALE,
                    "GitHub action credential window could not be opened");
        }
    }

    GitAskPassEnvironment environment() {
        requireOpen();
        return new GitAskPassEnvironment(program, secretFile);
    }

    Path program() {
        return program;
    }

    Path secretFile() {
        return secretFile;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        try {
            if (Files.isRegularFile(secretFile)) {
                byte[] zeros = new byte[Math.toIntExact(Files.size(secretFile))];
                Files.write(secretFile, zeros);
                Arrays.fill(zeros, (byte) 0);
            }
        } catch (IOException ignored) {
            // Deletion below is still attempted; public failures remain secret-free.
        }
        if (!cleanup(directory)) {
            throw new GitHubPushException(
                    GitHubPushErrorCode.UNKNOWN,
                    "GitHub action credential cleanup requires Worker intervention");
        }
        closed = true;
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("GitHub AskPass session is closed");
        }
    }

    private static boolean validSecret(byte[] secret) {
        if (secret.length == 0 || secret.length > 8 * 1024) {
            return false;
        }
        for (byte value : secret) {
            int unsigned = Byte.toUnsignedInt(value);
            if (unsigned < 0x21 || unsigned > 0x7e) {
                return false;
            }
        }
        return true;
    }

    private static Path initializeRoot(Path configuredRoot) {
        Path root = Objects.requireNonNull(configuredRoot, "configuredRoot")
                .toAbsolutePath()
                .normalize();
        try {
            Files.createDirectories(root);
            if (Files.isSymbolicLink(root)) {
                throw new IOException("symbolic link root");
            }
            Files.setPosixFilePermissions(root, DIRECTORY_PERMISSIONS);
            return root.toRealPath();
        } catch (IOException | UnsupportedOperationException failure) {
            throw new GitHubPushException(
                    GitHubPushErrorCode.AUTHORITY_STALE,
                    "GitHub action credential root is unavailable");
        }
    }

    private static boolean cleanup(Path directory) {
        if (directory == null) {
            return true;
        }
        try {
            Files.deleteIfExists(directory.resolve("askpass"));
            Files.deleteIfExists(directory.resolve("secret"));
            Files.deleteIfExists(directory);
        } catch (IOException ignored) {
            // A path-free cleanup failure is returned below.
        }
        return !Files.exists(directory);
    }
}
