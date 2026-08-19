package io.crewscope.infrastructure.workspace.repository;

import io.crewscope.domain.coding.AllowedPathSet;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;

/** Mutation-specific path, case, type and UTF-8 checks over one managed Worktree. */
final class CodingFilesystemPathPolicy {

    private static final LinkOption[] NOFOLLOW = {LinkOption.NOFOLLOW_LINKS};

    private final Path root;
    private final RepositoryInspectionPathGuard pathGuard;

    CodingFilesystemPathPolicy(
            Path root, String containerRoot, AllowedPathSet allowedPaths) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        this.pathGuard = new RepositoryInspectionPathGuard(
                this.root, containerRoot, allowedPaths, 1);
    }

    MutationPath requireMissingFile(String supplied) {
        String relative = requireMutablePath(supplied);
        requireNoCaseCollision(relative);
        Path host = pathGuard.toHostPath(relative);
        if (Files.exists(host, NOFOLLOW)) {
            throw new CodingFilesystemException(
                    CodingFilesystemError.PATH_EXISTS,
                    "Coding filesystem destination already exists");
        }
        return new MutationPath(
                relative,
                pathGuard.toContainerPath(relative),
                host,
                CodingFilesystemPathWitness.capture(root, relative));
    }

    ExistingTextFile requireTextFile(String supplied, long maximumBytes) {
        String relative = requireMutablePath(supplied);
        requireNoCaseCollision(relative);
        Path host = pathGuard.toHostPath(relative);
        CodingFilesystemPathWitness witness =
                CodingFilesystemPathWitness.capture(root, relative);
        BasicFileAttributes attributes = attributes(host);
        if (!attributes.isRegularFile()) {
            throw new CodingFilesystemException(
                    CodingFilesystemError.NOT_REGULAR_FILE,
                    "Coding filesystem mutations only support regular files");
        }
        if (attributes.size() > maximumBytes || attributes.size() > Integer.MAX_VALUE) {
            throw new CodingFilesystemException(
                    CodingFilesystemError.BUDGET_EXCEEDED,
                    "Repository file exceeds the effective single-file budget");
        }
        try {
            byte[] bytes = Files.readAllBytes(host);
            witness.verify();
            String content = decodeUtf8(bytes);
            requireText(content);
            return new ExistingTextFile(
                    new MutationPath(
                            relative,
                            pathGuard.toContainerPath(relative),
                            host,
                            witness),
                    content,
                    bytes.length);
        } catch (CodingFilesystemException failure) {
            throw failure;
        } catch (IOException failure) {
            throw filesystemFailure(failure);
        }
    }

    MutationPath requireExistingRegularFile(String supplied) {
        String relative = requireMutablePath(supplied);
        requireNoCaseCollision(relative);
        Path host = pathGuard.toHostPath(relative);
        CodingFilesystemPathWitness witness =
                CodingFilesystemPathWitness.capture(root, relative);
        if (!Files.exists(host, NOFOLLOW)) {
            throw new CodingFilesystemException(
                    CodingFilesystemError.FILE_NOT_FOUND,
                    "Coding filesystem source file does not exist");
        }
        if (!attributes(host).isRegularFile()) {
            throw new CodingFilesystemException(
                    CodingFilesystemError.NOT_REGULAR_FILE,
                    "Coding filesystem mutations only support regular files");
        }
        return new MutationPath(
                relative, pathGuard.toContainerPath(relative), host, witness);
    }

    void verifyRegularFile(MutationPath path, long expectedMaximumBytes) {
        String relative = requireMutablePath(path.relative());
        requireNoCaseCollision(relative);
        BasicFileAttributes attributes = attributes(pathGuard.toHostPath(relative));
        if (!attributes.isRegularFile()) {
            throw filesystemFailure(null);
        }
        if (attributes.size() > expectedMaximumBytes) {
            throw new CodingFilesystemException(
                    CodingFilesystemError.BUDGET_EXCEEDED,
                    "Repository file exceeds the effective single-file budget");
        }
    }

    void verifyAbsent(String supplied) {
        String relative = requireMutablePath(supplied);
        requireNoCaseCollision(relative);
        if (Files.exists(pathGuard.toHostPath(relative), NOFOLLOW)) {
            throw filesystemFailure(null);
        }
    }

    private String requireMutablePath(String supplied) {
        try {
            String relative = pathGuard.requirePath(supplied);
            if (".".equals(relative)) {
                throw new CodingFilesystemException(
                        CodingFilesystemError.INVALID_PATH,
                        "Repository root cannot be mutated by a Coding filesystem tool");
            }
            return relative;
        } catch (CodingFilesystemException failure) {
            throw failure;
        } catch (RepositoryInspectionException failure) {
            throw translate(failure);
        }
    }

    private void requireNoCaseCollision(String relative) {
        Path parent = root;
        for (String component : relative.split("/")) {
            if (!Files.isDirectory(parent, NOFOLLOW)) {
                return;
            }
            try (DirectoryStream<Path> children = Files.newDirectoryStream(parent)) {
                boolean exact = false;
                for (Path child : children) {
                    String name = child.getFileName().toString();
                    if (name.equals(component)) {
                        exact = true;
                    } else if (name.equalsIgnoreCase(component)) {
                        throw new CodingFilesystemException(
                                CodingFilesystemError.CASE_COLLISION,
                                "Repository path has an ambiguous case-insensitive sibling");
                    }
                }
                if (!exact) {
                    return;
                }
                parent = parent.resolve(component);
            } catch (CodingFilesystemException failure) {
                throw failure;
            } catch (IOException failure) {
                throw filesystemFailure(failure);
            }
        }
    }

    private static BasicFileAttributes attributes(Path path) {
        try {
            return Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (java.nio.file.NoSuchFileException missing) {
            throw new CodingFilesystemException(
                    CodingFilesystemError.FILE_NOT_FOUND,
                    "Coding filesystem source file does not exist");
        } catch (IOException failure) {
            throw filesystemFailure(failure);
        }
    }

    private static String decodeUtf8(byte[] bytes) {
        try {
            CharBuffer content = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            return content.toString();
        } catch (CharacterCodingException failure) {
            throw new CodingFilesystemException(
                    CodingFilesystemError.BINARY_FILE,
                    "Binary repository files are unavailable to Coding filesystem tools");
        }
    }

    static void requireText(String content) {
        if (Objects.requireNonNull(content, "content").indexOf('\0') >= 0) {
            throw new CodingFilesystemException(
                    CodingFilesystemError.BINARY_FILE,
                    "Binary repository content is unavailable to Coding filesystem tools");
        }
    }

    private static CodingFilesystemException translate(
            RepositoryInspectionException failure) {
        CodingFilesystemError error = switch (failure.error()) {
            case INVALID_PATH -> CodingFilesystemError.INVALID_PATH;
            case PATH_NOT_ALLOWED -> CodingFilesystemError.PATH_NOT_ALLOWED;
            case SENSITIVE_PATH -> CodingFilesystemError.SENSITIVE_PATH;
            case SYMBOLIC_LINK -> CodingFilesystemError.SYMBOLIC_LINK;
            default -> CodingFilesystemError.INVALID_REQUEST;
        };
        return new CodingFilesystemException(error, failure.getMessage(), failure);
    }

    private static CodingFilesystemException filesystemFailure(Throwable cause) {
        return new CodingFilesystemException(
                CodingFilesystemError.FILESYSTEM_FAILED,
                "Controlled Coding filesystem operation failed",
                cause);
    }

    record MutationPath(
            String relative,
            String container,
            Path host,
            CodingFilesystemPathWitness witness) {}

    record ExistingTextFile(MutationPath path, String content, long bytes) {}
}
