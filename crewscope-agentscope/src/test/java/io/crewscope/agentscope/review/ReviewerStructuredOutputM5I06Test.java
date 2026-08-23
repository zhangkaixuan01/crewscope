package io.crewscope.agentscope.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.crewscope.agentscope.StrictStructuredOutputDecoder;
import io.crewscope.application.review.output.ReviewFindingListV1;
import io.crewscope.application.review.output.ReviewerStructuredOutputSpecs;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** M5-I06 locks the production ReviewFindingListV1 schema before Evidence resolution. */
class ReviewerStructuredOutputM5I06Test {

    @Test
    void decodesACompleteFindingAndAllowsAValidEmptyReview() {
        ReviewFindingListV1 clean = decode(root(List.of()));
        ReviewFindingListV1 defect = decode(root(List.of(finding())));

        assertEquals(List.of(), clean.findings());
        assertEquals(1, defect.toCandidates().size());
        assertEquals("Null handling is missing", defect.toCandidates().get(0).title());
    }

    @Test
    void rejectsGateEffectFingerprintUnknownFieldsAndMissingEvidence() {
        Map<String, Object> gate = root(List.of());
        gate.put("gateDecision", "APPROVED");
        assertThrows(IllegalArgumentException.class, () -> decode(gate));

        Map<String, Object> effect = root(List.of(finding()));
        findingOf(effect).put("effect", "GATE");
        assertThrows(IllegalArgumentException.class, () -> decode(effect));

        Map<String, Object> fingerprint = root(List.of(finding()));
        findingOf(fingerprint).put("fingerprint", "a".repeat(64));
        assertThrows(IllegalArgumentException.class, () -> decode(fingerprint));

        Map<String, Object> noEvidence = root(List.of(finding()));
        findingOf(noEvidence).put("evidence", List.of());
        assertThrows(IllegalArgumentException.class, () -> decode(noEvidence));
    }

    private static ReviewFindingListV1 decode(Map<String, Object> value) {
        return (ReviewFindingListV1) StrictStructuredOutputDecoder.decode(
                value, ReviewerStructuredOutputSpecs.REVIEW_FINDING_LIST);
    }

    private static Map<String, Object> root(List<Map<String, Object>> findings) {
        LinkedHashMap<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", "1");
        root.put("findings", findings);
        return root;
    }

    private static Map<String, Object> finding() {
        LinkedHashMap<String, Object> finding = new LinkedHashMap<>();
        finding.put("severity", "HIGH");
        finding.put("category", "CORRECTNESS");
        finding.put("title", "Null handling is missing");
        finding.put("claim", "Calling strip on a null name throws");
        finding.put("suggestedFix", "Guard null before trimming");
        LinkedHashMap<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("canonicalPath", "src/main/java/io/crewscope/Greeting.java");
        evidence.put("startLine", 13);
        evidence.put("endLine", 13);
        evidence.put("diffArtifactId", "10000000-0000-4000-8000-000000000001");
        evidence.put("diffArtifactHash", "1".repeat(64));
        evidence.put("diffManifestHash", "2".repeat(64));
        evidence.put("testEvidenceId", "10000000-0000-4000-8000-000000000002");
        evidence.put("testEvidenceHash", "3".repeat(64));
        evidence.put("acceptanceCriterionIndex", 1);
        finding.put("evidence", List.of(evidence));
        return finding;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> findingOf(Map<String, Object> root) {
        return (Map<String, Object>) ((List<?>) root.get("findings")).get(0);
    }
}
