package io.crewscope.application.artifact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.shared.id.AggregateId;
import io.crewscope.domain.shared.id.ArtifactId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Executable contract for every future ArtifactStore implementation. */
class ArtifactStoreContractTest {

    private static final ArtifactId ARTIFACT_ID =
            ArtifactId.from("01989ee2-f6b0-7cda-97c4-1b337043d401");
    private static final OrganizationId ORGANIZATION_ID =
            OrganizationId.from("01989ee2-f6b0-7cda-97c4-1b337043d402");
    private static final OrganizationId OTHER_ORGANIZATION_ID =
            OrganizationId.from("01989ee2-f6b0-7cda-97c4-1b337043d403");
    private static final TeamId TEAM_ID =
            TeamId.from("01989ee2-f6b0-7cda-97c4-1b337043d404");
    private static final WorkspaceId WORKSPACE_ID =
            WorkspaceId.from("01989ee2-f6b0-7cda-97c4-1b337043d405");
    private static final PrincipalId OWNER_ID =
            PrincipalId.from("01989ee2-f6b0-7cda-97c4-1b337043d406");
    private static final PrincipalId MEMBER_ID =
            PrincipalId.from("01989ee2-f6b0-7cda-97c4-1b337043d407");
    private static final UtcTimestamp CREATED_AT =
            UtcTimestamp.parse("2026-08-07T10:00:00Z");

    @Test
    void canonicalizesAndCalculatesSha256() {
        Sha256Hash upperCase = new Sha256Hash(
                "2CF24DBA5FB0A30E26E83B2AC5B9E29E1B161E5C1FA7425E73043362938B9824");

        assertEquals(
                "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
                upperCase.toString());
        assertEquals(upperCase, Sha256Hash.digestUtf8("hello"));
        assertThrows(IllegalArgumentException.class, () -> new Sha256Hash("abc"));
        assertThrows(IllegalArgumentException.class, () -> new Sha256Hash("g".repeat(64)));
    }

    @Test
    void validatesWriteMetadataAndVisibilityCoordinates() {
        assertThrows(
                IllegalArgumentException.class,
                () -> request(
                        ArtifactScope.organization(ORGANIZATION_ID),
                        ArtifactVisibility.TEAM,
                        Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () -> request(
                        ArtifactScope.team(ORGANIZATION_ID, TEAM_ID),
                        ArtifactVisibility.WORKSPACE,
                        Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ArtifactWriteRequest(
                        ARTIFACT_ID,
                        ArtifactScope.organization(ORGANIZATION_ID),
                        "text/plain\r\nsecret: value",
                        5,
                        Sha256Hash.digestUtf8("hello"),
                        ArtifactDataClassification.INTERNAL,
                        ArtifactVisibility.ORGANIZATION,
                        Optional.empty(),
                        producer()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ArtifactWriteRequest(
                        ARTIFACT_ID,
                        ArtifactScope.organization(ORGANIZATION_ID),
                        "text / plain",
                        5,
                        Sha256Hash.digestUtf8("hello"),
                        ArtifactDataClassification.INTERNAL,
                        ArtifactVisibility.ORGANIZATION,
                        Optional.empty(),
                        producer()));
        assertThrows(
                IllegalArgumentException.class,
                () -> request(
                        ArtifactScope.organization(ORGANIZATION_ID),
                        ArtifactVisibility.ORGANIZATION,
                        Optional.of(Duration.ofNanos(999))));
    }

    @Test
    void derivesRetentionAtUtcMicrosecondPrecision() {
        ArtifactWriteRequest request = request(
                scopedArtifact(),
                ArtifactVisibility.WORKSPACE,
                Optional.of(Duration.ofHours(2)));

        assertEquals(
                Optional.of(UtcTimestamp.parse("2026-08-07T12:00:00Z")),
                request.retentionUntil(CREATED_AT));
        assertTrue(descriptor(request, Optional.empty()).matches(request));
        assertFalse(descriptor(request, Optional.empty()).matches(new ArtifactWriteRequest(
                request.artifactId(),
                request.scope(),
                request.contentType(),
                request.declaredSize() + 1,
                request.expectedHash(),
                request.dataClassification(),
                request.visibility(),
                request.timeToLive(),
                request.producer())));
        ArtifactWriteRequest overflowing = request(
                scopedArtifact(),
                ArtifactVisibility.WORKSPACE,
                Optional.of(Duration.ofSeconds(Long.MAX_VALUE)));
        assertThrows(IllegalArgumentException.class, () -> overflowing.retentionUntil(CREATED_AT));
    }

    @Test
    void normalizesProducerTraceAndRejectsNilExecutionIds() {
        ArtifactProducer traced = new ArtifactProducer(
                OWNER_ID,
                Optional.of(UUID.randomUUID()),
                Optional.empty(),
                Optional.empty(),
                Optional.of(" 0AF7651916CD43DD8448EB211C80319C "));

        assertEquals(
                Optional.of("0af7651916cd43dd8448eb211c80319c"), traced.traceId());
        assertThrows(
                IllegalArgumentException.class,
                () -> new ArtifactProducer(
                        OWNER_ID,
                        Optional.of(AggregateId.NIL_UUID),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ArtifactProducer(
                        OWNER_ID,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of("00000000000000000000000000000000")));
    }

    @Test
    void authorizesPrivateWorkspaceTeamAndOrganizationVisibility() {
        ArtifactAccessContext owner = access(OWNER_ID, Set.of(), Set.of());
        ArtifactAccessContext member = access(MEMBER_ID, Set.of(TEAM_ID), Set.of(WORKSPACE_ID));
        ArtifactAccessContext unrelated = access(MEMBER_ID, Set.of(), Set.of());
        ArtifactAccessContext otherOrganization = new ArtifactAccessContext(
                OTHER_ORGANIZATION_ID,
                MEMBER_ID,
                Set.of(TEAM_ID),
                Set.of(WORKSPACE_ID));

        assertTrue(owner.allows(descriptor(ArtifactVisibility.PRIVATE, scopedArtifact())));
        assertFalse(member.allows(descriptor(ArtifactVisibility.PRIVATE, scopedArtifact())));
        assertTrue(member.allows(descriptor(ArtifactVisibility.WORKSPACE, scopedArtifact())));
        assertTrue(member.allows(descriptor(ArtifactVisibility.TEAM, scopedArtifact())));
        assertTrue(unrelated.allows(descriptor(
                ArtifactVisibility.ORGANIZATION,
                ArtifactScope.organization(ORGANIZATION_ID))));
        assertFalse(otherOrganization.allows(descriptor(
                ArtifactVisibility.ORGANIZATION,
                ArtifactScope.organization(ORGANIZATION_ID))));
    }

    @Test
    void makesAccessScopeCollectionsImmutable() {
        ArtifactAccessContext context = access(MEMBER_ID, Set.of(TEAM_ID), Set.of(WORKSPACE_ID));

        assertThrows(
                UnsupportedOperationException.class,
                () -> context.authorizedTeamIds().add(TeamId.generate()));
        assertThrows(
                UnsupportedOperationException.class,
                () -> context.authorizedWorkspaceIds().add(WorkspaceId.generate()));
    }

    @Test
    void expiresAtTheRetentionBoundaryAndBlocksTombstonedContent() {
        ArtifactWriteRequest request = request(
                scopedArtifact(),
                ArtifactVisibility.WORKSPACE,
                Optional.of(Duration.ofHours(2)));
        ArtifactDescriptor active = descriptor(request, Optional.empty());

        assertTrue(active.isContentAvailableAt(UtcTimestamp.parse("2026-08-07T11:59:59Z")));
        assertTrue(active.isExpiredAt(UtcTimestamp.parse("2026-08-07T12:00:00Z")));
        assertFalse(active.isContentAvailableAt(UtcTimestamp.parse("2026-08-07T12:00:00Z")));

        ArtifactDescriptor tombstoned = descriptor(
                request,
                Optional.of(tombstone("2026-08-07T11:00:00Z")));
        assertFalse(tombstoned.isContentAvailableAt(UtcTimestamp.parse("2026-08-07T11:00:00Z")));
    }

    @Test
    void requiresTombstoneAndCompletedRetentionBeforePurge() {
        ArtifactWriteRequest retained = request(
                scopedArtifact(),
                ArtifactVisibility.WORKSPACE,
                Optional.of(Duration.ofHours(2)));
        ArtifactDescriptor tombstoned = descriptor(
                retained,
                Optional.of(tombstone("2026-08-07T11:00:00Z")));

        assertFalse(tombstoned.isPurgeEligibleAt(UtcTimestamp.parse("2026-08-07T11:30:00Z")));
        assertTrue(tombstoned.isPurgeEligibleAt(UtcTimestamp.parse("2026-08-07T12:00:00Z")));
        assertFalse(descriptor(retained, Optional.empty())
                .isPurgeEligibleAt(UtcTimestamp.parse("2026-08-07T13:00:00Z")));

        ArtifactWriteRequest indefinite = request(
                scopedArtifact(), ArtifactVisibility.WORKSPACE, Optional.empty());
        assertTrue(descriptor(indefinite, Optional.of(tombstone("2026-08-07T11:00:00Z")))
                .isPurgeEligibleAt(UtcTimestamp.parse("2026-08-07T11:00:00Z")));
    }

    @Test
    void normalizesTombstoneDetailForIdempotentRetries() {
        ArtifactTombstone tombstone = new ArtifactTombstone(
                ArtifactTombstoneReason.USER_REQUESTED,
                Optional.of("  User removed attachment  "),
                OWNER_ID,
                CREATED_AT);

        assertEquals(Optional.of("User removed attachment"), tombstone.detail());
        assertTrue(tombstone.matches(
                ArtifactTombstoneReason.USER_REQUESTED,
                Optional.of("User removed attachment")));
        assertFalse(tombstone.matches(
                ArtifactTombstoneReason.SECURITY_POLICY,
                Optional.of("User removed attachment")));
    }

    @Test
    void rejectsUnsafeStorageLocationsAndInvalidLifecycleOrder() {
        ArtifactWriteRequest request = request(
                scopedArtifact(), ArtifactVisibility.WORKSPACE, Optional.empty());

        assertThrows(
                IllegalArgumentException.class,
                () -> descriptor(
                        request,
                        URI.create("https://user:token@example.test/object?signature=secret"),
                        Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () -> descriptor(
                        request,
                        URI.create("file:///var/lib/crewscope/artifacts/object"),
                        Optional.of(tombstone("2026-08-07T09:59:59Z"))));
    }

    @Test
    void exposesStableSafeStoreFailures() {
        ArtifactStoreException exception = new ArtifactStoreException(
                ArtifactStoreError.INTEGRITY_VIOLATION,
                " Artifact content does not match its declaration ");

        assertEquals(ArtifactStoreError.INTEGRITY_VIOLATION, exception.error());
        assertEquals("Artifact content does not match its declaration", exception.getMessage());
        assertThrows(
                IllegalArgumentException.class,
                () -> new ArtifactStoreException(ArtifactStoreError.STORAGE_FAILURE, " "));
    }

    @Test
    void boundsPhysicalCleanupRequests() {
        ArtifactPurgeRequest request = new ArtifactPurgeRequest(CREATED_AT, 100);

        assertEquals(CREATED_AT, request.eligibleBefore());
        assertEquals(100, request.batchSize());
        assertThrows(IllegalArgumentException.class, () -> new ArtifactPurgeRequest(CREATED_AT, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ArtifactPurgeRequest(
                        CREATED_AT, ArtifactPurgeRequest.MAX_BATCH_SIZE + 1));
    }

    private static ArtifactWriteRequest request(
            ArtifactScope scope,
            ArtifactVisibility visibility,
            Optional<Duration> timeToLive) {
        return new ArtifactWriteRequest(
                ARTIFACT_ID,
                scope,
                "text/plain; charset=utf-8",
                5,
                Sha256Hash.digestUtf8("hello"),
                ArtifactDataClassification.INTERNAL,
                visibility,
                timeToLive,
                producer());
    }

    private static ArtifactDescriptor descriptor(
            ArtifactVisibility visibility, ArtifactScope scope) {
        return descriptor(request(scope, visibility, Optional.empty()), Optional.empty());
    }

    private static ArtifactDescriptor descriptor(
            ArtifactWriteRequest request, Optional<ArtifactTombstone> tombstone) {
        return descriptor(
                request,
                URI.create("file:///var/lib/crewscope/artifacts/object"),
                tombstone);
    }

    private static ArtifactDescriptor descriptor(
            ArtifactWriteRequest request,
            URI storageUri,
            Optional<ArtifactTombstone> tombstone) {
        return new ArtifactDescriptor(
                request.artifactId(),
                request.scope(),
                request.contentType(),
                request.declaredSize(),
                request.expectedHash(),
                request.dataClassification(),
                request.visibility(),
                storageUri,
                ArtifactEncryption.NONE,
                request.producer(),
                CREATED_AT,
                request.retentionUntil(CREATED_AT),
                tombstone);
    }

    private static ArtifactProducer producer() {
        return ArtifactProducer.principal(OWNER_ID);
    }

    private static ArtifactScope scopedArtifact() {
        return ArtifactScope.workspace(
                ORGANIZATION_ID, Optional.of(TEAM_ID), WORKSPACE_ID);
    }

    private static ArtifactAccessContext access(
            PrincipalId principalId,
            Set<TeamId> teams,
            Set<WorkspaceId> workspaces) {
        return new ArtifactAccessContext(ORGANIZATION_ID, principalId, teams, workspaces);
    }

    private static ArtifactTombstone tombstone(String timestamp) {
        return new ArtifactTombstone(
                ArtifactTombstoneReason.USER_REQUESTED,
                Optional.empty(),
                OWNER_ID,
                UtcTimestamp.parse(timestamp));
    }
}
