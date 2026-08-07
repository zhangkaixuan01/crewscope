package io.crewscope.infrastructure.artifact;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.artifact.ArtifactAccessContext;
import io.crewscope.application.artifact.ArtifactContent;
import io.crewscope.application.artifact.ArtifactDataClassification;
import io.crewscope.application.artifact.ArtifactDescriptor;
import io.crewscope.application.artifact.ArtifactMutationContext;
import io.crewscope.application.artifact.ArtifactProducer;
import io.crewscope.application.artifact.ArtifactPurgeRequest;
import io.crewscope.application.artifact.ArtifactScope;
import io.crewscope.application.artifact.ArtifactStoreError;
import io.crewscope.application.artifact.ArtifactStoreException;
import io.crewscope.application.artifact.ArtifactTombstone;
import io.crewscope.application.artifact.ArtifactTombstoneReason;
import io.crewscope.application.artifact.ArtifactVisibility;
import io.crewscope.application.artifact.ArtifactWriteRequest;
import io.crewscope.application.artifact.Sha256Hash;
import io.crewscope.domain.shared.id.ArtifactId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

/** Proves the Filesystem adapter against real files, locks, streams and atomic moves. */
class FilesystemArtifactStoreIntegrationTest {

    private static final Instant BASE_TIME = Instant.parse("2026-08-07T10:00:00Z");
    private static final OrganizationId ORGANIZATION_ID = OrganizationId.generate();
    private static final OrganizationId OTHER_ORGANIZATION_ID = OrganizationId.generate();
    private static final TeamId TEAM_ID = TeamId.generate();
    private static final WorkspaceId WORKSPACE_ID = WorkspaceId.generate();
    private static final PrincipalId OWNER_ID = PrincipalId.generate();
    private static final PrincipalId MEMBER_ID = PrincipalId.generate();
    private static final byte[] CONTENT = "crewscope artifact".getBytes(StandardCharsets.UTF_8);

    @TempDir
    private Path temporaryDirectory;

    private MutableClock clock;
    private FilesystemArtifactStore store;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(BASE_TIME);
        store = new FilesystemArtifactStore(
                temporaryDirectory, new ObjectMapper(), clock);
    }

    @Test
    void streamsContentWithoutClosingCallerInputAndPersistsCanonicalMetadata() throws Exception {
        ArtifactWriteRequest request = request(
                ArtifactId.generate(),
                workspaceScope(),
                ArtifactVisibility.WORKSPACE,
                CONTENT,
                Optional.of(Duration.ofHours(2)));
        TrackingInputStream source = new TrackingInputStream(CONTENT);

        ArtifactDescriptor descriptor = store.put(request, source);

        assertFalse(source.closed);
        assertEquals(UtcTimestamp.from(BASE_TIME), descriptor.createdAt());
        assertEquals(
                Optional.of(UtcTimestamp.from(BASE_TIME.plus(Duration.ofHours(2)))),
                descriptor.retentionUntil());
        assertTrue(Files.isRegularFile(Path.of(descriptor.storageUri())));
        assertEquals(1, regularFileCount(temporaryDirectory.resolve("objects")));
        assertEquals(1, regularFileCount(temporaryDirectory.resolve("references")));
        assertEquals(0, regularFileCount(temporaryDirectory.resolve("temporary")));

        ArtifactDescriptor headed = store.head(request.artifactId(), workspaceMember())
                .orElseThrow();
        assertEquals(descriptor, headed);
        try (ArtifactContent content = store.get(request.artifactId(), workspaceMember())
                .orElseThrow()) {
            assertArrayEquals(CONTENT, content.stream().readAllBytes());
        }
    }

    @Test
    void rejectsSizeAndHashMismatchWithoutPublishingOrLeavingTemporaryFiles() {
        ArtifactWriteRequest wrongSize = new ArtifactWriteRequest(
                ArtifactId.generate(),
                workspaceScope(),
                "application/octet-stream",
                CONTENT.length - 1,
                Sha256Hash.digest(CONTENT),
                ArtifactDataClassification.INTERNAL,
                ArtifactVisibility.WORKSPACE,
                Optional.empty(),
                producer());

        assertError(
                ArtifactStoreError.INTEGRITY_VIOLATION,
                () -> store.put(wrongSize, new ByteArrayInputStream(CONTENT)));
        ArtifactWriteRequest wrongHash = new ArtifactWriteRequest(
                ArtifactId.generate(),
                workspaceScope(),
                "application/octet-stream",
                CONTENT.length,
                Sha256Hash.digestUtf8("different"),
                ArtifactDataClassification.INTERNAL,
                ArtifactVisibility.WORKSPACE,
                Optional.empty(),
                producer());
        assertError(
                ArtifactStoreError.INTEGRITY_VIOLATION,
                () -> store.put(wrongHash, new ByteArrayInputStream(CONTENT)));

        assertEquals(0, regularFileCount(temporaryDirectory.resolve("temporary")));
        assertEquals(0, regularFileCount(temporaryDirectory.resolve("references")));
        assertEquals(0, regularFileCount(temporaryDirectory.resolve("objects")));
    }

    @Test
    void makesIdenticalRetryIdempotentAndRejectsDifferentMetadata() {
        ArtifactId artifactId = ArtifactId.generate();
        ArtifactWriteRequest request = request(
                artifactId,
                workspaceScope(),
                ArtifactVisibility.WORKSPACE,
                CONTENT,
                Optional.empty());
        ArtifactDescriptor first = store.put(request, new ByteArrayInputStream(CONTENT));

        ArtifactDescriptor retried = store.put(request, new FailOnReadInputStream());

        assertEquals(first, retried);
        ArtifactWriteRequest changed = request(
                artifactId,
                workspaceScope(),
                ArtifactVisibility.TEAM,
                CONTENT,
                Optional.empty());
        assertError(
                ArtifactStoreError.CONFLICT,
                () -> store.put(changed, new ByteArrayInputStream(CONTENT)));
        assertEquals(1, regularFileCount(temporaryDirectory.resolve("objects")));
        assertEquals(1, regularFileCount(temporaryDirectory.resolve("references")));
    }

    @Test
    void serializesConcurrentWritesForTheSameArtifactId() throws Exception {
        ArtifactWriteRequest request = request(
                ArtifactId.generate(),
                workspaceScope(),
                ArtifactVisibility.WORKSPACE,
                CONTENT,
                Optional.empty());
        int workers = 8;
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        try {
            List<Future<ArtifactDescriptor>> futures = new ArrayList<>();
            for (int index = 0; index < workers; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return store.put(request, new ByteArrayInputStream(CONTENT));
                }));
            }
            ready.await();
            start.countDown();

            List<ArtifactDescriptor> descriptors = new ArrayList<>();
            for (Future<ArtifactDescriptor> future : futures) {
                descriptors.add(future.get());
            }
            descriptors.forEach(descriptor -> assertEquals(descriptors.get(0), descriptor));
        } finally {
            executor.shutdownNow();
        }

        assertEquals(1, regularFileCount(temporaryDirectory.resolve("objects")));
        assertEquals(1, regularFileCount(temporaryDirectory.resolve("references")));
        assertEquals(0, regularFileCount(temporaryDirectory.resolve("temporary")));
    }

    @Test
    void restoresDescriptorsAndContentAfterStoreRestart() throws Exception {
        ArtifactWriteRequest request = request(
                ArtifactId.generate(),
                workspaceScope(),
                ArtifactVisibility.WORKSPACE,
                CONTENT,
                Optional.empty());
        ArtifactDescriptor created = store.put(request, new ByteArrayInputStream(CONTENT));

        FilesystemArtifactStore restarted = new FilesystemArtifactStore(
                temporaryDirectory, new ObjectMapper(), new MutableClock(BASE_TIME));

        assertEquals(created, restarted.head(request.artifactId(), workspaceMember()).orElseThrow());
        try (ArtifactContent content = restarted.get(request.artifactId(), workspaceMember())
                .orElseThrow()) {
            assertArrayEquals(CONTENT, content.stream().readAllBytes());
        }
    }

    @Test
    void enforcesVisibilityAndHidesExistenceAcrossOrganizations() {
        ArtifactWriteRequest privateRequest = request(
                ArtifactId.generate(),
                workspaceScope(),
                ArtifactVisibility.PRIVATE,
                CONTENT,
                Optional.empty());
        ArtifactWriteRequest workspaceRequest = request(
                ArtifactId.generate(),
                workspaceScope(),
                ArtifactVisibility.WORKSPACE,
                CONTENT,
                Optional.empty());
        ArtifactWriteRequest organizationRequest = request(
                ArtifactId.generate(),
                ArtifactScope.organization(ORGANIZATION_ID),
                ArtifactVisibility.ORGANIZATION,
                CONTENT,
                Optional.empty());
        store.put(privateRequest, new ByteArrayInputStream(CONTENT));
        store.put(workspaceRequest, new ByteArrayInputStream(CONTENT));
        store.put(organizationRequest, new ByteArrayInputStream(CONTENT));

        assertTrue(store.head(privateRequest.artifactId(), owner()).isPresent());
        assertTrue(store.head(privateRequest.artifactId(), workspaceMember()).isEmpty());
        assertTrue(store.get(workspaceRequest.artifactId(), unrelatedMember()).isEmpty());
        assertTrue(store.get(workspaceRequest.artifactId(), workspaceMember()).isPresent());
        assertTrue(store.head(organizationRequest.artifactId(), unrelatedMember()).isPresent());
        assertTrue(store.head(organizationRequest.artifactId(), otherOrganization()).isEmpty());
    }

    @Test
    void stopsContentReadsAtTheTtlBoundaryWhileKeepingAuthorizedHead() {
        ArtifactWriteRequest request = request(
                ArtifactId.generate(),
                workspaceScope(),
                ArtifactVisibility.WORKSPACE,
                CONTENT,
                Optional.of(Duration.ofHours(1)));
        store.put(request, new ByteArrayInputStream(CONTENT));

        clock.set(BASE_TIME.plus(Duration.ofHours(1)).minusNanos(1_000));
        assertTrue(store.get(request.artifactId(), workspaceMember()).isPresent());
        clock.set(BASE_TIME.plus(Duration.ofHours(1)));

        assertTrue(store.get(request.artifactId(), workspaceMember()).isEmpty());
        assertTrue(store.head(request.artifactId(), workspaceMember()).isPresent());
    }

    @Test
    void persistsIdempotentTombstoneAndRejectsCrossOrganizationMutation() {
        ArtifactWriteRequest request = request(
                ArtifactId.generate(),
                workspaceScope(),
                ArtifactVisibility.WORKSPACE,
                CONTENT,
                Optional.empty());
        store.put(request, new ByteArrayInputStream(CONTENT));

        assertError(
                ArtifactStoreError.ACCESS_DENIED,
                () -> store.tombstone(
                        request.artifactId(),
                        new ArtifactMutationContext(OTHER_ORGANIZATION_ID, MEMBER_ID),
                        ArtifactTombstoneReason.USER_REQUESTED,
                        Optional.empty()));
        ArtifactTombstone first = store.tombstone(
                        request.artifactId(),
                        mutation(),
                        ArtifactTombstoneReason.USER_REQUESTED,
                        Optional.of(" Remove attachment "))
                .orElseThrow();
        clock.set(BASE_TIME.plusSeconds(10));
        ArtifactTombstone retried = store.tombstone(
                        request.artifactId(),
                        mutation(),
                        ArtifactTombstoneReason.USER_REQUESTED,
                        Optional.of("Remove attachment"))
                .orElseThrow();

        assertEquals(first, retried);
        assertTrue(store.get(request.artifactId(), workspaceMember()).isEmpty());
        assertEquals(Optional.of(first), store.head(request.artifactId(), workspaceMember())
                .orElseThrow()
                .tombstone());
        assertError(
                ArtifactStoreError.CONFLICT,
                () -> store.tombstone(
                        request.artifactId(),
                        mutation(),
                        ArtifactTombstoneReason.SECURITY_POLICY,
                        Optional.empty()));
    }

    @Test
    void detectsBlobAndDescriptorCorruption() throws Exception {
        ArtifactWriteRequest blobRequest = request(
                ArtifactId.generate(),
                workspaceScope(),
                ArtifactVisibility.WORKSPACE,
                CONTENT,
                Optional.empty());
        ArtifactDescriptor descriptor = store.put(
                blobRequest, new ByteArrayInputStream(CONTENT));
        Files.write(
                Path.of(descriptor.storageUri()),
                "crewscope artifacX".getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.TRUNCATE_EXISTING);

        assertError(
                ArtifactStoreError.INTEGRITY_VIOLATION,
                () -> store.get(blobRequest.artifactId(), workspaceMember()));
        assertError(
                ArtifactStoreError.INTEGRITY_VIOLATION,
                () -> store.put(blobRequest, new ByteArrayInputStream(CONTENT)));

        ArtifactWriteRequest metadataRequest = request(
                ArtifactId.generate(),
                workspaceScope(),
                ArtifactVisibility.WORKSPACE,
                "metadata".getBytes(StandardCharsets.UTF_8),
                Optional.empty());
        store.put(metadataRequest, new ByteArrayInputStream("metadata".getBytes(StandardCharsets.UTF_8)));
        Files.writeString(
                referencePath(metadataRequest.artifactId()),
                "{\"schemaVersion\":1,\"artifactId\":\"broken\"}",
                StandardOpenOption.TRUNCATE_EXISTING);

        assertError(
                ArtifactStoreError.INTEGRITY_VIOLATION,
                () -> store.head(metadataRequest.artifactId(), workspaceMember()));
    }

    @Test
    void rejectsDescriptorWhoseArtifactIdDoesNotMatchItsReferencePath() throws Exception {
        ArtifactWriteRequest request = request(
                ArtifactId.generate(),
                workspaceScope(),
                ArtifactVisibility.WORKSPACE,
                CONTENT,
                Optional.empty());
        store.put(request, new ByteArrayInputStream(CONTENT));
        Path forgedReference = referencePath(ArtifactId.generate());
        Files.createDirectories(forgedReference.getParent());
        Files.copy(referencePath(request.artifactId()), forgedReference);

        assertError(
                ArtifactStoreError.INTEGRITY_VIOLATION,
                () -> store.purgeTombstoned(new ArtifactPurgeRequest(
                        UtcTimestamp.from(BASE_TIME), 10)));
    }

    @Test
    void purgesInBoundedBatchesAndRetainsSharedBlobUntilLastReference() {
        ArtifactWriteRequest first = request(
                ArtifactId.generate(),
                workspaceScope(),
                ArtifactVisibility.WORKSPACE,
                CONTENT,
                Optional.empty());
        ArtifactWriteRequest second = request(
                ArtifactId.generate(),
                workspaceScope(),
                ArtifactVisibility.WORKSPACE,
                CONTENT,
                Optional.empty());
        ArtifactDescriptor firstDescriptor = store.put(first, new ByteArrayInputStream(CONTENT));
        store.put(second, new ByteArrayInputStream(CONTENT));
        store.tombstone(
                first.artifactId(), mutation(), ArtifactTombstoneReason.USER_REQUESTED, Optional.empty());
        store.tombstone(
                second.artifactId(), mutation(), ArtifactTombstoneReason.USER_REQUESTED, Optional.empty());

        List<ArtifactId> firstBatch = store.purgeTombstoned(new ArtifactPurgeRequest(
                UtcTimestamp.from(BASE_TIME), 1));

        assertEquals(1, firstBatch.size());
        assertTrue(Files.exists(Path.of(firstDescriptor.storageUri())));
        assertEquals(1, regularFileCount(temporaryDirectory.resolve("references")));
        List<ArtifactId> secondBatch = store.purgeTombstoned(new ArtifactPurgeRequest(
                UtcTimestamp.from(BASE_TIME), 10));
        assertEquals(1, secondBatch.size());
        assertFalse(Files.exists(Path.of(firstDescriptor.storageUri())));
        assertEquals(0, regularFileCount(temporaryDirectory.resolve("references")));
    }

    @Test
    void waitsForRetentionBeforePurgingATombstonedArtifact() {
        ArtifactWriteRequest request = request(
                ArtifactId.generate(),
                workspaceScope(),
                ArtifactVisibility.WORKSPACE,
                CONTENT,
                Optional.of(Duration.ofHours(1)));
        ArtifactDescriptor descriptor = store.put(request, new ByteArrayInputStream(CONTENT));
        store.tombstone(
                request.artifactId(), mutation(), ArtifactTombstoneReason.USER_REQUESTED, Optional.empty());

        assertTrue(store.purgeTombstoned(new ArtifactPurgeRequest(
                        UtcTimestamp.from(BASE_TIME.plus(Duration.ofMinutes(59))), 10))
                .isEmpty());
        assertTrue(Files.exists(Path.of(descriptor.storageUri())));
        assertEquals(
                List.of(request.artifactId()),
                store.purgeTombstoned(new ArtifactPurgeRequest(
                        UtcTimestamp.from(BASE_TIME.plus(Duration.ofHours(1))), 10)));
        assertFalse(Files.exists(Path.of(descriptor.storageUri())));
    }

    private ArtifactWriteRequest request(
            ArtifactId artifactId,
            ArtifactScope scope,
            ArtifactVisibility visibility,
            byte[] content,
            Optional<Duration> ttl) {
        return new ArtifactWriteRequest(
                artifactId,
                scope,
                "application/octet-stream",
                content.length,
                Sha256Hash.digest(content),
                ArtifactDataClassification.INTERNAL,
                visibility,
                ttl,
                producer());
    }

    private static ArtifactScope workspaceScope() {
        return ArtifactScope.workspace(
                ORGANIZATION_ID, Optional.of(TEAM_ID), WORKSPACE_ID);
    }

    private static ArtifactProducer producer() {
        return ArtifactProducer.principal(OWNER_ID);
    }

    private static ArtifactAccessContext owner() {
        return new ArtifactAccessContext(
                ORGANIZATION_ID, OWNER_ID, Set.of(), Set.of());
    }

    private static ArtifactAccessContext workspaceMember() {
        return new ArtifactAccessContext(
                ORGANIZATION_ID, MEMBER_ID, Set.of(TEAM_ID), Set.of(WORKSPACE_ID));
    }

    private static ArtifactAccessContext unrelatedMember() {
        return new ArtifactAccessContext(
                ORGANIZATION_ID, MEMBER_ID, Set.of(), Set.of());
    }

    private static ArtifactAccessContext otherOrganization() {
        return new ArtifactAccessContext(
                OTHER_ORGANIZATION_ID, MEMBER_ID, Set.of(TEAM_ID), Set.of(WORKSPACE_ID));
    }

    private static ArtifactMutationContext mutation() {
        return new ArtifactMutationContext(ORGANIZATION_ID, OWNER_ID);
    }

    private Path referencePath(ArtifactId artifactId) {
        String value = artifactId.toString();
        return temporaryDirectory
                .resolve("references")
                .resolve(value.substring(0, 2))
                .resolve(value + ".json");
    }

    private static long regularFileCount(Path directory) {
        if (!Files.exists(directory)) {
            return 0;
        }
        try (var paths = Files.walk(directory)) {
            return paths.filter(Files::isRegularFile).count();
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
    }

    private static void assertError(ArtifactStoreError error, Runnable action) {
        ArtifactStoreException exception = assertThrows(ArtifactStoreException.class, action::run);
        assertEquals(error, exception.error());
    }

    private static final class TrackingInputStream extends ByteArrayInputStream {
        private boolean closed;

        private TrackingInputStream(byte[] content) {
            super(content);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }

    private static final class FailOnReadInputStream extends InputStream {
        @Override
        public int read() {
            throw new AssertionError("Idempotent retry must not consume the input stream");
        }
    }

    private static final class MutableClock extends Clock {
        private volatile Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void set(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException("MutableClock supports UTC only");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
