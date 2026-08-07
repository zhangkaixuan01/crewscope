package io.crewscope.infrastructure.artifact;

import io.crewscope.application.artifact.ArtifactAccessContext;
import io.crewscope.application.artifact.ArtifactContent;
import io.crewscope.application.artifact.ArtifactDescriptor;
import io.crewscope.application.artifact.ArtifactEncryption;
import io.crewscope.application.artifact.ArtifactMutationContext;
import io.crewscope.application.artifact.ArtifactPurgeRequest;
import io.crewscope.application.artifact.ArtifactStore;
import io.crewscope.application.artifact.ArtifactStoreError;
import io.crewscope.application.artifact.ArtifactStoreException;
import io.crewscope.application.artifact.ArtifactTombstone;
import io.crewscope.application.artifact.ArtifactTombstoneReason;
import io.crewscope.application.artifact.ArtifactWriteRequest;
import io.crewscope.application.artifact.Sha256Hash;
import io.crewscope.domain.shared.id.ArtifactId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;
import tools.jackson.databind.ObjectMapper;

/** Development ArtifactStore using content-addressed files and atomic JSON Sidecars. */
public class FilesystemArtifactStore implements ArtifactStore {

    private static final int BUFFER_SIZE = 64 * 1024;
    private static final int MAX_METADATA_SIZE = 64 * 1024;
    private static final int LOCK_STRIPES = 64;

    private final Path root;
    private final Path objectsRoot;
    private final Path referencesRoot;
    private final Path temporaryRoot;
    private final Path locksRoot;
    private final FilesystemArtifactMetadataJsonCodec metadataCodec;
    private final Clock clock;
    private final ReentrantLock[] lockStripes;

    public FilesystemArtifactStore(Path root, ObjectMapper objectMapper, Clock clock) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        this.objectsRoot = this.root.resolve("objects").resolve("sha256");
        this.referencesRoot = this.root.resolve("references");
        this.temporaryRoot = this.root.resolve("temporary");
        this.locksRoot = this.root.resolve("locks");
        this.metadataCodec = new FilesystemArtifactMetadataJsonCodec(objectMapper);
        this.clock = Objects.requireNonNull(clock, "clock");
        this.lockStripes = createLockStripes();
        initializeDirectories();
    }

    @Override
    public ArtifactDescriptor put(ArtifactWriteRequest request, InputStream content) {
        ArtifactWriteRequest writeRequest = Objects.requireNonNull(request, "request");
        InputStream source = Objects.requireNonNull(content, "content");
        return withFileLock("artifact", writeRequest.artifactId().toString(), () -> {
            Path reference = referencePath(writeRequest.artifactId());
            if (Files.exists(reference, LinkOption.NOFOLLOW_LINKS)) {
                ArtifactDescriptor existing = readDescriptor(reference);
                requireArtifactId(existing, writeRequest.artifactId());
                if (!existing.matches(writeRequest)) {
                    throw conflict("Artifact ID already refers to different metadata or content");
                }
                verifyDescriptorLocation(existing);
                verifyBlob(existing);
                return existing;
            }

            Path staged = null;
            Throwable failure = null;
            try {
                staged = temporaryRoot.resolve(UUID.randomUUID() + ".part");
                StagedContent stagedContent = stageAndVerify(writeRequest, source, staged);
                Path stagedPath = staged;
                return withFileLock("content", stagedContent.hash().toString(), () -> {
                    Path blob = blobPath(stagedContent.hash());
                    publishOrVerifyBlob(stagedPath, blob, stagedContent);
                    ArtifactDescriptor descriptor = descriptor(writeRequest, blob);
                    writeDescriptorAtomically(reference, descriptor);
                    return descriptor;
                });
            } catch (IOException | RuntimeException | Error exception) {
                failure = exception;
                throw exception;
            } finally {
                deleteTemporary(staged, failure);
            }
        });
    }

    @Override
    public Optional<ArtifactDescriptor> head(
            ArtifactId artifactId, ArtifactAccessContext accessContext) {
        ArtifactId id = Objects.requireNonNull(artifactId, "artifactId");
        ArtifactAccessContext access = Objects.requireNonNull(accessContext, "accessContext");
        return withFileLock("artifact", id.toString(), () -> {
            Optional<ArtifactDescriptor> descriptor = readDescriptorIfPresent(referencePath(id), id);
            if (descriptor.isEmpty() || !access.allows(descriptor.orElseThrow())) {
                return Optional.empty();
            }
            verifyDescriptorLocation(descriptor.orElseThrow());
            return descriptor;
        });
    }

    @Override
    public Optional<ArtifactContent> get(
            ArtifactId artifactId, ArtifactAccessContext accessContext) {
        ArtifactId id = Objects.requireNonNull(artifactId, "artifactId");
        ArtifactAccessContext access = Objects.requireNonNull(accessContext, "accessContext");
        return withFileLock("artifact", id.toString(), () -> {
            Optional<ArtifactDescriptor> result = readDescriptorIfPresent(referencePath(id), id);
            if (result.isEmpty()) {
                return Optional.empty();
            }
            ArtifactDescriptor descriptor = result.orElseThrow();
            if (!access.allows(descriptor)
                    || !descriptor.isContentAvailableAt(UtcTimestamp.from(clock.instant()))) {
                return Optional.empty();
            }
            verifyDescriptorLocation(descriptor);
            return withFileLock(
                    "content",
                    descriptor.sha256().toString(),
                    () -> Optional.of(openVerifiedContent(descriptor)));
        });
    }

    @Override
    public Optional<ArtifactTombstone> tombstone(
            ArtifactId artifactId,
            ArtifactMutationContext mutationContext,
            ArtifactTombstoneReason reason,
            Optional<String> detail) {
        ArtifactId id = Objects.requireNonNull(artifactId, "artifactId");
        ArtifactMutationContext mutation =
                Objects.requireNonNull(mutationContext, "mutationContext");
        ArtifactTombstoneReason tombstoneReason = Objects.requireNonNull(reason, "reason");
        Optional<String> tombstoneDetail = Objects.requireNonNull(detail, "detail");
        return withFileLock("artifact", id.toString(), () -> {
            Path reference = referencePath(id);
            Optional<ArtifactDescriptor> result = readDescriptorIfPresent(reference, id);
            if (result.isEmpty()) {
                return Optional.empty();
            }
            ArtifactDescriptor existing = result.orElseThrow();
            if (!mutation.organizationId().equals(existing.scope().organizationId())) {
                throw accessDenied("Artifact lifecycle mutation is outside the authorized organization");
            }
            if (existing.tombstone().isPresent()) {
                ArtifactTombstone committed = existing.tombstone().orElseThrow();
                if (committed.matches(tombstoneReason, tombstoneDetail)) {
                    return Optional.of(committed);
                }
                throw conflict("Artifact already has a different Tombstone");
            }
            ArtifactTombstone created = new ArtifactTombstone(
                    tombstoneReason,
                    tombstoneDetail,
                    mutation.principalId(),
                    UtcTimestamp.from(clock.instant()));
            ArtifactDescriptor updated = withTombstone(existing, created);
            writeDescriptorAtomically(reference, updated);
            return Optional.of(created);
        });
    }

    @Override
    public List<ArtifactId> purgeTombstoned(ArtifactPurgeRequest request) {
        ArtifactPurgeRequest purge = Objects.requireNonNull(request, "request");
        List<Path> candidates = listReferenceFiles();
        List<ArtifactId> removed = new ArrayList<>(Math.min(candidates.size(), purge.batchSize()));
        for (Path candidate : candidates) {
            if (removed.size() >= purge.batchSize()) {
                break;
            }
            ArtifactDescriptor snapshot = readDescriptor(candidate);
            requireReferencePath(candidate, snapshot);
            if (!snapshot.isPurgeEligibleAt(purge.eligibleBefore())) {
                continue;
            }
            ArtifactId id = snapshot.artifactId();
            boolean deleted = withFileLock("artifact", id.toString(), () -> purgeOne(id, purge));
            if (deleted) {
                removed.add(id);
            }
        }
        return List.copyOf(removed);
    }

    private boolean purgeOne(ArtifactId artifactId, ArtifactPurgeRequest request) throws IOException {
        Path reference = referencePath(artifactId);
        Optional<ArtifactDescriptor> result = readDescriptorIfPresent(reference, artifactId);
        if (result.isEmpty() || !result.orElseThrow().isPurgeEligibleAt(request.eligibleBefore())) {
            return false;
        }
        ArtifactDescriptor descriptor = result.orElseThrow();
        return withFileLock("content", descriptor.sha256().toString(), () -> {
            boolean hasOtherReference = hasOtherReference(descriptor.sha256(), artifactId);
            if (!Files.deleteIfExists(reference)) {
                return false;
            }
            if (!hasOtherReference) {
                Files.deleteIfExists(blobPath(descriptor.sha256()));
            }
            return true;
        });
    }

    private ArtifactDescriptor descriptor(ArtifactWriteRequest request, Path blob) {
        UtcTimestamp createdAt = UtcTimestamp.from(clock.instant());
        return new ArtifactDescriptor(
                request.artifactId(),
                request.scope(),
                request.contentType(),
                request.declaredSize(),
                request.expectedHash(),
                request.dataClassification(),
                request.visibility(),
                blob.toUri(),
                ArtifactEncryption.NONE,
                request.producer(),
                createdAt,
                request.retentionUntil(createdAt),
                Optional.empty());
    }

    private StagedContent stageAndVerify(
            ArtifactWriteRequest request, InputStream content, Path staged) throws IOException {
        MessageDigest digest = Sha256Hash.newDigest();
        long size = 0;
        byte[] buffer = new byte[BUFFER_SIZE];
        try (FileChannel output = FileChannel.open(
                staged, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            int read;
            while ((read = content.read(buffer)) != -1) {
                if (read == 0) {
                    continue;
                }
                try {
                    size = Math.addExact(size, read);
                } catch (ArithmeticException exception) {
                    throw integrityViolation("Artifact content exceeds the supported size", exception);
                }
                if (size > request.declaredSize()) {
                    throw integrityViolation("Artifact content is larger than its declared size");
                }
                digest.update(buffer, 0, read);
                ByteBuffer bytes = ByteBuffer.wrap(buffer, 0, read);
                while (bytes.hasRemaining()) {
                    output.write(bytes);
                }
            }
            output.force(true);
        }
        Sha256Hash actualHash = new Sha256Hash(HexFormat.of().formatHex(digest.digest()));
        if (size != request.declaredSize() || !actualHash.equals(request.expectedHash())) {
            throw integrityViolation("Artifact content does not match its declared size and SHA-256");
        }
        return new StagedContent(size, actualHash);
    }

    private void publishOrVerifyBlob(
            Path staged, Path blob, StagedContent content) throws IOException {
        Files.createDirectories(blob.getParent());
        if (Files.exists(blob, LinkOption.NOFOLLOW_LINKS)) {
            verifyBlob(blob, content.size(), content.hash());
            return;
        }
        atomicMove(staged, blob, false);
    }

    private void verifyBlob(ArtifactDescriptor descriptor) {
        verifyBlob(blobPath(descriptor.sha256()), descriptor.size(), descriptor.sha256());
    }

    private void verifyBlob(Path blob, long expectedSize, Sha256Hash expectedHash) {
        try {
            if (!Files.isRegularFile(blob, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(blob) != expectedSize) {
                throw integrityViolation("Artifact content size does not match its Descriptor");
            }
            try (FileChannel channel = FileChannel.open(
                    blob, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                verifyBlob(channel, expectedSize, expectedHash);
            }
        } catch (ArtifactStoreException exception) {
            throw exception;
        } catch (IOException exception) {
            throw integrityViolation("Artifact content could not be verified", exception);
        }
    }

    private ArtifactContent openVerifiedContent(ArtifactDescriptor descriptor) {
        Path blob = blobPath(descriptor.sha256());
        FileChannel channel = null;
        try {
            if (!Files.isRegularFile(blob, LinkOption.NOFOLLOW_LINKS)) {
                throw integrityViolation("Artifact content is not a regular file");
            }
            channel = FileChannel.open(blob, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
            verifyBlob(channel, descriptor.size(), descriptor.sha256());
            channel.position(0);
            InputStream stream = new BufferedInputStream(Channels.newInputStream(channel), BUFFER_SIZE);
            channel = null;
            return new ArtifactContent(descriptor, stream);
        } catch (ArtifactStoreException exception) {
            throw exception;
        } catch (IOException exception) {
            throw storageFailure("Artifact content could not be opened", exception);
        } finally {
            closeFailedContentChannel(channel);
        }
    }

    private void verifyBlob(FileChannel channel, long expectedSize, Sha256Hash expectedHash)
            throws IOException {
        if (channel.size() != expectedSize) {
            throw integrityViolation("Artifact content size does not match its Descriptor");
        }
        MessageDigest digest = Sha256Hash.newDigest();
        ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);
        while (channel.read(buffer) != -1) {
            if (buffer.position() == 0) {
                continue;
            }
            buffer.flip();
            digest.update(buffer);
            buffer.clear();
        }
        Sha256Hash actual = new Sha256Hash(HexFormat.of().formatHex(digest.digest()));
        if (!actual.equals(expectedHash)) {
            throw integrityViolation("Artifact content SHA-256 does not match its Descriptor");
        }
    }

    private Optional<ArtifactDescriptor> readDescriptorIfPresent(
            Path reference, ArtifactId expectedId) {
        if (!Files.exists(reference, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        ArtifactDescriptor descriptor = readDescriptor(reference);
        requireArtifactId(descriptor, expectedId);
        return Optional.of(descriptor);
    }

    private ArtifactDescriptor readDescriptor(Path reference) {
        try {
            if (!Files.isRegularFile(reference, LinkOption.NOFOLLOW_LINKS)) {
                throw integrityViolation("Artifact Descriptor is not a regular file");
            }
            long size = Files.size(reference);
            if (size < 1 || size > MAX_METADATA_SIZE) {
                throw integrityViolation("Artifact Descriptor size is invalid");
            }
            return metadataCodec.decode(Files.readAllBytes(reference));
        } catch (ArtifactStoreException exception) {
            throw exception;
        } catch (InvalidArtifactMetadataException exception) {
            throw integrityViolation("Artifact Descriptor is invalid", exception);
        } catch (IOException exception) {
            throw storageFailure("Artifact Descriptor could not be read", exception);
        }
    }

    private void writeDescriptorAtomically(Path reference, ArtifactDescriptor descriptor)
            throws IOException {
        byte[] document = metadataCodec.encode(descriptor);
        if (document.length > MAX_METADATA_SIZE) {
            throw integrityViolation("Artifact Descriptor exceeds the supported size");
        }
        Files.createDirectories(reference.getParent());
        Path temporary = temporaryRoot.resolve(UUID.randomUUID() + ".json.tmp");
        Throwable failure = null;
        try {
            try (FileChannel output = FileChannel.open(
                    temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer bytes = ByteBuffer.wrap(document);
                while (bytes.hasRemaining()) {
                    output.write(bytes);
                }
                output.force(true);
            }
            atomicMove(temporary, reference, true);
        } catch (IOException | RuntimeException | Error exception) {
            failure = exception;
            throw exception;
        } finally {
            deleteTemporary(temporary, failure);
        }
    }

    private void verifyDescriptorLocation(ArtifactDescriptor descriptor) {
        if (!blobPath(descriptor.sha256()).toUri().equals(descriptor.storageUri())) {
            throw integrityViolation("Artifact Descriptor storage location is invalid");
        }
    }

    private boolean hasOtherReference(Sha256Hash hash, ArtifactId excludedId) {
        for (Path reference : listReferenceFiles()) {
            ArtifactDescriptor candidate = readDescriptor(reference);
            requireReferencePath(reference, candidate);
            if (!candidate.artifactId().equals(excludedId) && candidate.sha256().equals(hash)) {
                return true;
            }
        }
        return false;
    }

    private List<Path> listReferenceFiles() {
        try (Stream<Path> paths = Files.walk(referencesRoot)) {
            return paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        } catch (IOException exception) {
            throw storageFailure("Artifact references could not be listed", exception);
        }
    }

    private ArtifactDescriptor withTombstone(
            ArtifactDescriptor descriptor, ArtifactTombstone tombstone) {
        return new ArtifactDescriptor(
                descriptor.artifactId(),
                descriptor.scope(),
                descriptor.contentType(),
                descriptor.size(),
                descriptor.sha256(),
                descriptor.dataClassification(),
                descriptor.visibility(),
                descriptor.storageUri(),
                descriptor.encryption(),
                descriptor.producer(),
                descriptor.createdAt(),
                descriptor.retentionUntil(),
                Optional.of(tombstone));
    }

    private void requireArtifactId(ArtifactDescriptor descriptor, ArtifactId expectedId) {
        if (!descriptor.artifactId().equals(expectedId)) {
            throw integrityViolation("Artifact Descriptor ID does not match its reference path");
        }
    }

    private void requireReferencePath(Path reference, ArtifactDescriptor descriptor) {
        if (!referencePath(descriptor.artifactId()).equals(reference.toAbsolutePath().normalize())) {
            throw integrityViolation("Artifact Descriptor ID does not match its reference path");
        }
    }

    private Path blobPath(Sha256Hash hash) {
        String value = hash.toString();
        return objectsRoot.resolve(value.substring(0, 2)).resolve(value + ".blob");
    }

    private Path referencePath(ArtifactId artifactId) {
        String value = artifactId.toString();
        return referencesRoot.resolve(value.substring(0, 2)).resolve(value + ".json");
    }

    private void atomicMove(Path source, Path target, boolean replaceExisting) throws IOException {
        try {
            if (replaceExisting) {
                Files.move(
                        source,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            }
        } catch (AtomicMoveNotSupportedException exception) {
            throw storageFailure("Artifact root does not support atomic file moves", exception);
        }
    }

    private <T> T withFileLock(String type, String key, IoOperation<T> operation) {
        String lockKey = type + ':' + key;
        ReentrantLock localLock = lockStripes[Math.floorMod(lockKey.hashCode(), lockStripes.length)];
        localLock.lock();
        try {
            Path lockDirectory = locksRoot.resolve(type);
            Files.createDirectories(lockDirectory);
            Path lockPath = lockDirectory.resolve(key + ".lock");
            try (FileChannel channel = FileChannel.open(
                            lockPath,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.WRITE);
                    FileLock ignored = channel.lock()) {
                return operation.execute();
            }
        } catch (ArtifactStoreException exception) {
            throw exception;
        } catch (IOException exception) {
            throw storageFailure("Artifact filesystem operation failed", exception);
        } finally {
            localLock.unlock();
        }
    }

    private void initializeDirectories() {
        try {
            Files.createDirectories(root);
            Files.createDirectories(objectsRoot);
            Files.createDirectories(referencesRoot);
            Files.createDirectories(temporaryRoot);
            Files.createDirectories(locksRoot);
        } catch (IOException exception) {
            throw storageFailure("Artifact filesystem root could not be initialized", exception);
        }
    }

    private void deleteTemporary(Path temporary, Throwable failure) throws IOException {
        if (temporary == null) {
            return;
        }
        try {
            Files.deleteIfExists(temporary);
        } catch (IOException exception) {
            if (failure != null) {
                failure.addSuppressed(exception);
                return;
            }
            throw exception;
        }
    }

    private void closeFailedContentChannel(FileChannel channel) {
        if (channel == null) {
            return;
        }
        try {
            channel.close();
        } catch (IOException ignored) {
            // The original open or integrity failure remains the actionable cause.
        }
    }

    private static ReentrantLock[] createLockStripes() {
        ReentrantLock[] locks = new ReentrantLock[LOCK_STRIPES];
        for (int index = 0; index < locks.length; index++) {
            locks[index] = new ReentrantLock();
        }
        return locks;
    }

    private static ArtifactStoreException integrityViolation(String message) {
        return new ArtifactStoreException(ArtifactStoreError.INTEGRITY_VIOLATION, message);
    }

    private static ArtifactStoreException integrityViolation(String message, Throwable cause) {
        return new ArtifactStoreException(ArtifactStoreError.INTEGRITY_VIOLATION, message, cause);
    }

    private static ArtifactStoreException conflict(String message) {
        return new ArtifactStoreException(ArtifactStoreError.CONFLICT, message);
    }

    private static ArtifactStoreException accessDenied(String message) {
        return new ArtifactStoreException(ArtifactStoreError.ACCESS_DENIED, message);
    }

    private static ArtifactStoreException storageFailure(String message, Throwable cause) {
        return new ArtifactStoreException(ArtifactStoreError.STORAGE_FAILURE, message, cause);
    }

    private record StagedContent(long size, Sha256Hash hash) {}

    @FunctionalInterface
    private interface IoOperation<T> {
        T execute() throws IOException;
    }
}
