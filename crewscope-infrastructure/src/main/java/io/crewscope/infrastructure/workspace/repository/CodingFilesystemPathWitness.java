package io.crewscope.infrastructure.workspace.repository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Captures existing path-component identities for a final pre-mutation TOCTOU check. */
final class CodingFilesystemPathWitness {

    private static final LinkOption[] NOFOLLOW = {LinkOption.NOFOLLOW_LINKS};

    private final List<ComponentIdentity> existing;
    private final Path firstMissing;

    private CodingFilesystemPathWitness(
            List<ComponentIdentity> existing, Path firstMissing) {
        this.existing = List.copyOf(existing);
        this.firstMissing = firstMissing;
    }

    static CodingFilesystemPathWitness capture(Path root, String relative) {
        Path current = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        List<ComponentIdentity> identities = new ArrayList<>();
        identities.add(identity(current));
        if (!".".equals(relative)) {
            for (String component : relative.split("/")) {
                current = current.resolve(component);
                if (!Files.exists(current, NOFOLLOW)) {
                    return new CodingFilesystemPathWitness(identities, current);
                }
                identities.add(identity(current));
            }
        }
        return new CodingFilesystemPathWitness(identities, null);
    }

    void verify() {
        for (ComponentIdentity expected : existing) {
            ComponentIdentity current;
            try {
                current = identity(expected.path());
            } catch (CodingFilesystemException ignored) {
                throw changed();
            }
            if (!expected.sameIdentity(current)) {
                throw changed();
            }
        }
        if (firstMissing != null && Files.exists(firstMissing, NOFOLLOW)) {
            throw changed();
        }
    }

    private static ComponentIdentity identity(Path path) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink()) {
                throw new CodingFilesystemException(
                        CodingFilesystemError.SYMBOLIC_LINK,
                        "Symbolic links are unavailable to Coding filesystem tools");
            }
            return new ComponentIdentity(
                    path,
                    attributes.fileKey(),
                    attributes.isDirectory(),
                    attributes.isRegularFile(),
                    attributes.size(),
                    attributes.lastModifiedTime().toMillis());
        } catch (CodingFilesystemException failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw new CodingFilesystemException(
                    CodingFilesystemError.TOCTOU_DETECTED,
                    "Repository path identity changed before mutation",
                    failure);
        }
    }

    private static CodingFilesystemException changed() {
        return new CodingFilesystemException(
                CodingFilesystemError.TOCTOU_DETECTED,
                "Repository path identity changed before mutation");
    }

    private record ComponentIdentity(
            Path path,
            Object fileKey,
            boolean directory,
            boolean regularFile,
            long size,
            long modifiedAt) {

        private boolean sameIdentity(ComponentIdentity other) {
            if (fileKey != null || other.fileKey != null) {
                return Objects.equals(fileKey, other.fileKey)
                        && directory == other.directory
                        && regularFile == other.regularFile;
            }
            return directory == other.directory
                    && regularFile == other.regularFile
                    && size == other.size
                    && modifiedAt == other.modifiedAt;
        }
    }
}
