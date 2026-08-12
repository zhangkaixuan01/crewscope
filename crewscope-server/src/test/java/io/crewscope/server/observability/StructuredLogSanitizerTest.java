package io.crewscope.server.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Proves field-level redaction and the bounded log-value boundary. */
class StructuredLogSanitizerTest {

    @Test
    void redactsCaseAndSeparatorInsensitiveSensitiveFields() {
        assertEquals(
                StructuredLogSanitizer.REDACTED,
                StructuredLogSanitizer.sanitize("Authorization", "Bearer private"));
        assertEquals(
                StructuredLogSanitizer.REDACTED,
                StructuredLogSanitizer.sanitize("provider_access-token", "private"));
        assertEquals(
                StructuredLogSanitizer.REDACTED,
                StructuredLogSanitizer.sanitize("credentialCiphertext", "private"));
        assertTrue(StructuredLogSanitizer.isSensitiveField("current_key_material"));
        assertTrue(StructuredLogSanitizer.isSensitiveField("system_prompt"));
        assertTrue(StructuredLogSanitizer.isSensitiveField("modelReasoning"));
        assertTrue(StructuredLogSanitizer.isSensitiveField("toolCallArguments"));
        assertTrue(StructuredLogSanitizer.isSensitiveField("raw-tool-result"));
        assertFalse(StructuredLogSanitizer.isSensitiveField("correlationId"));
        assertFalse(StructuredLogSanitizer.isSensitiveField("promptVersion"));
        assertFalse(StructuredLogSanitizer.isSensitiveField("toolName"));
        assertFalse(StructuredLogSanitizer.isSensitiveField("message"));
    }

    @Test
    void neutralizesControlCharactersThatCouldForgeLogRecords() {
        assertEquals(
                "first second third fourth",
                StructuredLogSanitizer.sanitize("message", "first\rsecond\nthird\tfourth"));
    }

    @Test
    void boundsNonSensitiveValues() {
        String sanitized = StructuredLogSanitizer.sanitize("provider", "x".repeat(400));

        assertEquals(StructuredLogSanitizer.MAX_VALUE_LENGTH, sanitized.length());
        assertTrue(sanitized.endsWith("…"));
    }
}
