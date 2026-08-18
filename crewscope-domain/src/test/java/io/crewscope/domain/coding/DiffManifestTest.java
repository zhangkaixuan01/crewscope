package io.crewscope.domain.coding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.ArtifactId;
import io.crewscope.domain.task.RuntimeContentHash;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DiffManifestTest {

    @Test
    void requiresOldPathOnlyForRenameAndCopyChanges() {
        for (DiffFileKind kind : List.of(
                DiffFileKind.ADDED,
                DiffFileKind.MODIFIED,
                DiffFileKind.DELETED,
                DiffFileKind.TYPE_CHANGED)) {
            assertThrows(
                    DomainValidationException.class,
                    () -> text("src/New.java", Optional.of("src/Old.java"), kind, 0, 0));
        }
        for (DiffFileKind kind : List.of(DiffFileKind.RENAMED, DiffFileKind.COPIED)) {
            assertThrows(
                    DomainValidationException.class,
                    () -> text("src/New.java", Optional.empty(), kind, 0, 0));
            assertThrows(
                    DomainValidationException.class,
                    () -> text(
                            "src/New.java", Optional.of("src/New.java"), kind, 0, 0));
            assertEquals(
                    new DiffPath("src/Old.java"),
                    text("src/New.java", Optional.of("src/Old.java"), kind, 0, 0)
                            .oldPath()
                            .orElseThrow());
        }
    }

    @Test
    void validatesBinaryStatisticsAndBoundedTextPreviewIntegrity() {
        RuntimeContentHash binaryPatch = RuntimeContentHash.sha256("binary patch");
        DiffFileEntry binary = DiffFileEntry.binary(
                "assets/logo.png", Optional.empty(), DiffFileKind.ADDED, binaryPatch);

        assertTrue(binary.binary());
        assertTrue(binary.patchTruncated());
        assertEquals(0, binary.additions());
        assertEquals(0, binary.deletions());
        assertFalse(binary.patchPreview().isPresent());
        assertThrows(
                DomainValidationException.class,
                () -> new DiffFileEntry(
                        binary.path(),
                        Optional.empty(),
                        binary.kind(),
                        1,
                        0,
                        true,
                        true,
                        binaryPatch,
                        Optional.empty()));

        String completePatch = "@@ -1 +1 @@\n-old\n+new\n";
        DiffFileEntry complete = DiffFileEntry.text(
                "README.md",
                Optional.empty(),
                DiffFileKind.MODIFIED,
                1,
                1,
                false,
                RuntimeContentHash.sha256(completePatch),
                Optional.of(completePatch));
        assertEquals(completePatch, complete.patchPreview().orElseThrow());
        assertThrows(
                DomainValidationException.class,
                () -> DiffFileEntry.text(
                        "README.md",
                        Optional.empty(),
                        DiffFileKind.MODIFIED,
                        1,
                        1,
                        false,
                        RuntimeContentHash.sha256("other"),
                        Optional.of(completePatch)));

        String fullPatch = completePatch + "+additional authority line\n";
        DiffFileEntry truncated = DiffFileEntry.text(
                "README.md",
                Optional.empty(),
                DiffFileKind.MODIFIED,
                2,
                1,
                true,
                RuntimeContentHash.sha256(fullPatch),
                Optional.of(completePatch));
        assertEquals(RuntimeContentHash.sha256(fullPatch), truncated.patchSha256());

        String maximumTrailingNewlinePreview = "line\n".repeat(DiffFileEntry.MAX_PREVIEW_LINES);
        DiffFileEntry.text(
                "docs/large.txt",
                Optional.empty(),
                DiffFileKind.ADDED,
                DiffFileEntry.MAX_PREVIEW_LINES,
                0,
                true,
                RuntimeContentHash.sha256("complete large patch"),
                Optional.of(maximumTrailingNewlinePreview));
        assertThrows(
                DomainValidationException.class,
                () -> DiffFileEntry.text(
                        "docs/large.txt",
                        Optional.empty(),
                        DiffFileKind.ADDED,
                        DiffFileEntry.MAX_PREVIEW_LINES + 1L,
                        0,
                        true,
                        RuntimeContentHash.sha256("complete large patch"),
                        Optional.of(maximumTrailingNewlinePreview + "overflow")));
    }

    @Test
    void acceptsOnlyCanonicalRepositoryRelativePaths() {
        for (String path : List.of(
                ".", "../secret", "src/../secret", "/tmp/file", "src//File.java", "src\\File.java")) {
            assertThrows(DomainValidationException.class, () -> new DiffPath(path));
        }

        DiffPath path = new DiffPath("crewscope-domain/src/Main.java");
        assertTrue(path.isWithin(CodingTargetAllowedPaths.of("crewscope-domain", "docs")));
        assertFalse(path.isWithin(CodingTargetAllowedPaths.of("docs")));
    }

    @Test
    void sortsByUnicodeCodePointAndRejectsDuplicateCurrentPaths() {
        String basicMultilingualPlane = "docs/\uE000.txt";
        String supplementary = "docs/\uD800\uDC00.txt";
        DiffFileEntry supplementaryEntry = text(
                supplementary, Optional.empty(), DiffFileKind.ADDED, 1, 0);
        DiffFileEntry basicEntry = text(
                basicMultilingualPlane, Optional.empty(), DiffFileKind.ADDED, 1, 0);

        DiffManifest manifest = DiffManifest.initial(List.of(supplementaryEntry, basicEntry));

        assertEquals(
                List.of(basicMultilingualPlane, supplementary),
                manifest.files().stream().map(entry -> entry.path().value()).toList());
        assertThrows(
                DomainValidationException.class,
                () -> DiffManifest.initial(List.of(basicEntry, basicEntry)));
    }

    @Test
    void computesFixtureStatisticsAndOrderIndependentContentHash() {
        List<DiffFileEntry> files = fixtureFiles();
        DiffManifest manifest = DiffManifest.initial(files);
        List<DiffFileEntry> reversed = new ArrayList<>(files);
        java.util.Collections.reverse(reversed);
        DiffManifest reordered = DiffManifest.initial(reversed);

        assertEquals(5, manifest.fileCount());
        assertEquals(49, manifest.additions());
        assertEquals(3, manifest.deletions());
        assertEquals(manifest.contentHash(), reordered.contentHash());
        assertEquals(DiffGeneration.first(), manifest.generation());
        assertThrows(
                UnsupportedOperationException.class,
                () -> manifest.files().add(text(
                        "extra.txt", Optional.empty(), DiffFileKind.ADDED, 1, 0)));
    }

    @Test
    void excludesBoundedPreviewBytesFromTheAuthorityContentHash() {
        RuntimeContentHash fullPatchHash = RuntimeContentHash.sha256("complete patch");
        DiffFileEntry firstPreview = DiffFileEntry.text(
                "src/Main.java",
                Optional.empty(),
                DiffFileKind.MODIFIED,
                1,
                1,
                true,
                fullPatchHash,
                Optional.of("preview one"));
        DiffFileEntry secondPreview = DiffFileEntry.text(
                "src/Main.java",
                Optional.empty(),
                DiffFileKind.MODIFIED,
                1,
                1,
                true,
                fullPatchHash,
                Optional.of("preview two"));

        assertEquals(
                DiffManifest.initial(List.of(firstPreview)).contentHash(),
                DiffManifest.initial(List.of(secondPreview)).contentHash());
    }

    @Test
    void advancesGenerationOnlyWhenAuthorityContentChanges() {
        DiffManifest initial = DiffManifest.initial(fixtureFiles());

        DiffManifest unchanged = DiffManifest.reconcile(initial, fixtureFiles());
        List<DiffFileEntry> changedFiles = new ArrayList<>(fixtureFiles());
        changedFiles.add(text("new.txt", Optional.empty(), DiffFileKind.ADDED, 1, 0));
        DiffManifest changed = DiffManifest.reconcile(initial, changedFiles);

        assertSame(initial, unchanged);
        assertEquals(new DiffGeneration(2), changed.generation());
        assertNotEquals(initial.contentHash(), changed.contentHash());
        assertThrows(DomainValidationException.class, () -> new DiffGeneration(0));
        assertThrows(
                DomainValidationException.class,
                () -> new DiffGeneration(Long.MAX_VALUE).next());
    }

    @Test
    void reconstitutionRejectsTamperedStatisticsAndHash() {
        DiffManifest manifest = DiffManifest.initial(fixtureFiles());

        assertThrows(
                DomainValidationException.class,
                () -> DiffManifest.reconstitute(
                        manifest.generation(),
                        manifest.files(),
                        manifest.fileCount() + 1,
                        manifest.additions(),
                        manifest.deletions(),
                        manifest.contentHash()));
        assertThrows(
                DomainValidationException.class,
                () -> DiffManifest.reconstitute(
                        manifest.generation(),
                        manifest.files(),
                        manifest.fileCount(),
                        manifest.additions() + 1,
                        manifest.deletions(),
                        manifest.contentHash()));
        assertThrows(
                DomainValidationException.class,
                () -> DiffManifest.reconstitute(
                        manifest.generation(),
                        manifest.files(),
                        manifest.fileCount(),
                        manifest.additions(),
                        manifest.deletions(),
                        RuntimeContentHash.sha256("tampered")));
    }

    @Test
    void closesEmptyPatchArtifactIntegrity() {
        PatchArtifactReference empty = new PatchArtifactReference(
                ArtifactId.generate(), 0, RuntimeContentHash.sha256(""));

        assertEquals(PatchArtifactReference.CONTENT_TYPE, empty.contentType());
        assertThrows(
                DomainValidationException.class,
                () -> new PatchArtifactReference(
                        ArtifactId.generate(), 0, RuntimeContentHash.sha256("not empty")));
        assertThrows(
                DomainValidationException.class,
                () -> new PatchArtifactReference(
                        ArtifactId.generate(), -1, RuntimeContentHash.sha256("")));
    }

    private static List<DiffFileEntry> fixtureFiles() {
        return List.of(
                text(
                        "docs/README.md",
                        Optional.of("README.md"),
                        DiffFileKind.RENAMED,
                        0,
                        0),
                text("docs/large.txt", Optional.empty(), DiffFileKind.ADDED, 40, 0),
                text("obsolete.txt", Optional.empty(), DiffFileKind.DELETED, 0, 1),
                text("src/Feature.java", Optional.empty(), DiffFileKind.ADDED, 7, 0),
                text("src/Greeting.java", Optional.empty(), DiffFileKind.MODIFIED, 2, 2));
    }

    private static DiffFileEntry text(
            String path,
            Optional<String> oldPath,
            DiffFileKind kind,
            long additions,
            long deletions) {
        String completePatch = path + ":" + kind + ":" + additions + ":" + deletions;
        return DiffFileEntry.text(
                path,
                oldPath,
                kind,
                additions,
                deletions,
                false,
                RuntimeContentHash.sha256(completePatch),
                Optional.of(completePatch));
    }
}
