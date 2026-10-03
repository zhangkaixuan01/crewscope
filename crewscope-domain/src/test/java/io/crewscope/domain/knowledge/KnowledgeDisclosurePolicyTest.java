package io.crewscope.domain.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.shared.error.DomainErrorCode;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Proves the S01 §3.5 disclosure gate: high-confidence families block, noise must not. */
class KnowledgeDisclosurePolicyTest {

    @Test
    void detectsEveryHighConfidenceFamily() {
        assertTrue(KnowledgeDisclosurePolicy.scan(
                "rotate the key AKIAIOSFODNN7EXAMPLE in staging").isPresent());
        assertTrue(KnowledgeDisclosurePolicy.scan(
                "call with sk-proj-abcdefghij0123456789abcdefghij0123456789").isPresent());
        assertTrue(KnowledgeDisclosurePolicy.scan(
                "legacy key sk-abcdefghij0123456789ABCDEFGHIJ stored offline").isPresent());
        assertTrue(KnowledgeDisclosurePolicy.scan(
                "token ghp_abcdefghijklmnopqrstuvwxyz0123456789AAA was leaked").isPresent());
        assertTrue(KnowledgeDisclosurePolicy.scan(
                "github_pat_11ABCDEFG0abcdefghij0123").isPresent());
        assertTrue(KnowledgeDisclosurePolicy.scan(
                "legacy bot credential xoxb-1234567890-AbCdEf").isPresent());
        assertTrue(KnowledgeDisclosurePolicy.scan(
                "maps key AIzaSyA1234567890abcdefghijklmnopqrstuv").isPresent());
        assertTrue(KnowledgeDisclosurePolicy.scan(
                "-----BEGIN RSA PRIVATE KEY-----").isPresent());
        assertTrue(KnowledgeDisclosurePolicy.scan(
                "-----BEGIN PRIVATE KEY-----").isPresent());
    }

    @Test
    void reportsTheFamilyNameOnly() {
        String secret = "AKIAIOSFODNN7EXAMPLE";
        Optional<String> family = KnowledgeDisclosurePolicy.scan("prefix " + secret + " suffix");

        assertEquals(Optional.of("aws_access_key_id"), family);
        assertFalse(family.orElseThrow().contains(secret));
    }

    @Test
    void requireDisclosableRefusesMatchesWithoutEchoingThem() {
        String secret = "ghp_abcdefghijklmnopqrstuvwxyz0123456789AAA";

        KnowledgeDisclosureViolationException failure = assertThrows(
                KnowledgeDisclosureViolationException.class,
                () -> KnowledgeDisclosurePolicy.requireDisclosable(
                        "Publishing the automation token " + secret,
                        "This runbook is safe."));

        assertEquals(
                DomainErrorCode.KNOWLEDGE_DISCLOSURE_DENIED, failure.error().code());
        assertEquals("github_token", failure.error().details().get("patternFamily"));
        assertFalse(failure.error().message().contains(secret));
        assertFalse(failure.error().details().toString().contains(secret));

        assertThrows(
                KnowledgeDisclosureViolationException.class,
                () -> KnowledgeDisclosurePolicy.requireDisclosable(
                        "Safe title", "sk-proj-abcdefghij0123456789abcdefghij0123456789"));
    }

    @Test
    void legitimateContentPassesUntouched() {
        // Shapes that must NOT trip the gate: normal prose about credentials,
        // low-confidence generic base64, password= assignments, short sk- fragments,
        // and hyphenated domain phrases whose "sk-" substring is not a key at all.
        KnowledgeDisclosurePolicy.requireDisclosable(
                "Credential rotation runbook",
                "Rotate the AWS access key quarterly. The old key keeps the prefix AKIA. "
                        + "Store secrets in the vault, never set password=hunter2 in CI. "
                        + "A short prefix like sk-abc is not a token. "
                        + "Base64 blobs such as dGhpcyBpcyBqdXN0IGEgc2FtcGxl are allowed.");
        assertTrue(KnowledgeDisclosurePolicy.scan("").isEmpty());
        assertTrue(KnowledgeDisclosurePolicy.scan((String) null).isEmpty());
        assertTrue(KnowledgeDisclosurePolicy.scan("no secrets here").isEmpty());
        assertTrue(KnowledgeDisclosurePolicy.scan(
                "task-management-guidelines").isEmpty());
        assertTrue(KnowledgeDisclosurePolicy.scan(
                "risk-assessment-checklist and task-execution-attempt-recovery").isEmpty());
        assertTrue(KnowledgeDisclosurePolicy.scan(
                "sk-management-guidelines").isEmpty());
        assertTrue(KnowledgeDisclosurePolicy.scan(
                "sk-proj-too-short-to-be-real").isEmpty());
    }
}
