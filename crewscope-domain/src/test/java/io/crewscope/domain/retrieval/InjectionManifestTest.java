package io.crewscope.domain.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.TaskExecutionId;
import java.util.List;
import org.junit.jupiter.api.Test;

class InjectionManifestTest {

    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);

    private static ManifestSourceRef ref(ManifestSourceStage stage) {
        return new ManifestSourceRef(
                ManifestSourceType.KNOWLEDGE_ENTRY,
                "6f9619ff-8b86-d011-b42d-00cf4fc964ff",
                3L,
                HASH_A,
                stage);
    }

    private static InjectionManifest manifest(
            int attempt,
            List<ManifestSourceRef> references,
            PromptBudgetSnapshot budget) {
        return new InjectionManifest(
                InjectionManifestId.generate(),
                TaskExecutionId.generate(),
                attempt,
                references,
                List.of(new TrimRecord(ManifestSourceType.KNOWLEDGE_ENTRY, 2, "budget exceeded")),
                budget,
                List.of(DegradationReasonCode.NO_MATCHING_GENERATION),
                UtcTimestamp.parse("2026-10-01T09:00:00Z"));
    }

    @Test
    void attemptMustBePositive() {
        PromptBudgetSnapshot budget = new PromptBudgetSnapshot(1000L, 300L, 400L, 200L);

        assertEquals(1, manifest(1, List.of(ref(ManifestSourceStage.INJECTED)), budget).attempt());
        assertThrows(DomainValidationException.class,
                () -> manifest(0, List.of(), budget));
        assertThrows(DomainValidationException.class,
                () -> manifest(-1, List.of(), budget));
    }

    @Test
    void referencesCarryTheFullProvenanceTriple() {
        PromptBudgetSnapshot budget = new PromptBudgetSnapshot(1000L, 300L, 400L, 200L);

        DomainValidationException blankSourceId = assertThrows(DomainValidationException.class,
                () -> new ManifestSourceRef(
                        ManifestSourceType.KNOWLEDGE_ENTRY, " ", 1L, HASH_A,
                        ManifestSourceStage.INJECTED));
        assertEquals("manifestSourceRef.sourceId", blankSourceId.error().details().get("field"));

        DomainValidationException zeroVersion = assertThrows(DomainValidationException.class,
                () -> new ManifestSourceRef(
                        ManifestSourceType.REPOSITORY_CHUNK, "chunk-1", 0L, HASH_A,
                        ManifestSourceStage.CANDIDATE));
        assertEquals("manifestSourceRef.version", zeroVersion.error().details().get("field"));

        DomainValidationException badHash = assertThrows(DomainValidationException.class,
                () -> new ManifestSourceRef(
                        ManifestSourceType.MEMORY_PREFERENCE, "pref-1", 2L, "not-a-hash",
                        ManifestSourceStage.INJECTED));
        assertEquals("manifestSourceRef.contentHash", badHash.error().details().get("field"));

        assertThrows(NullPointerException.class,
                () -> new ManifestSourceRef(
                        null, "pref-1", 2L, HASH_A, ManifestSourceStage.INJECTED));
        assertThrows(NullPointerException.class,
                () -> new ManifestSourceRef(
                        ManifestSourceType.SKILL_INSTRUCTION, "pref-1", 2L, HASH_A, null));
    }

    @Test
    void budgetLayersNeverExceedTheTotal() {
        assertThrows(DomainValidationException.class,
                () -> new PromptBudgetSnapshot(100L, 300L, 400L, 200L));
        assertThrows(DomainValidationException.class,
                () -> new PromptBudgetSnapshot(-1L, 0L, 0L, 0L));
        assertThrows(DomainValidationException.class,
                () -> new PromptBudgetSnapshot(100L, -1L, 0L, 0L));

        assertEquals(
                new PromptBudgetSnapshot(100L, 100L, 0L, 0L),
                new PromptBudgetSnapshot(100L, 100L, 0L, 0L));
        assertEquals(0L, new PromptBudgetSnapshot(0L, 0L, 0L, 0L).totalTokens());
    }

    @Test
    void degradationVocabularyIsFrozenAtExactlyThreeCodes() {
        assertEquals(
                "[RETRIEVAL_DISABLED, NO_MATCHING_GENERATION, EMBEDDING_PROVIDER_UNAVAILABLE]",
                java.util.Arrays.toString(DegradationReasonCode.values()));
    }

    @Test
    void nullCollectionsCollapseToEmptyAndInjectedFilterWorks() {
        InjectionManifest sparse = new InjectionManifest(
                InjectionManifestId.generate(),
                TaskExecutionId.generate(),
                1,
                null,
                null,
                new PromptBudgetSnapshot(0L, 0L, 0L, 0L),
                null,
                UtcTimestamp.parse("2026-10-01T09:00:00Z"));

        assertTrue(sparse.references().isEmpty());
        assertTrue(sparse.trims().isEmpty());
        assertTrue(sparse.degradations().isEmpty());

        InjectionManifest mixed = manifest(
                2,
                List.of(
                        ref(ManifestSourceStage.INJECTED),
                        new ManifestSourceRef(
                                ManifestSourceType.REPOSITORY_CHUNK, "chunk-9", 4L, HASH_B,
                                ManifestSourceStage.CANDIDATE)),
                new PromptBudgetSnapshot(1000L, 300L, 400L, 200L));

        assertEquals(2, mixed.references().size());
        assertEquals(1, mixed.injectedReferences().size());
        assertEquals(
                ManifestSourceType.KNOWLEDGE_ENTRY,
                mixed.injectedReferences().get(0).type());
    }

    @Test
    void trimRecordsMustReportAtLeastOneTrimmedItem() {
        assertThrows(DomainValidationException.class,
                () -> new TrimRecord(ManifestSourceType.KNOWLEDGE_ENTRY, 0, "budget exceeded"));
        assertThrows(DomainValidationException.class,
                () -> new TrimRecord(ManifestSourceType.KNOWLEDGE_ENTRY, 2, " "));
        assertThrows(NullPointerException.class,
                () -> new TrimRecord(null, 2, "budget exceeded"));
    }
}
