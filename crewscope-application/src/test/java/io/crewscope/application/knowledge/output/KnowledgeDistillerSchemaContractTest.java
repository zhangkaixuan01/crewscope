package io.crewscope.application.knowledge.output;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.knowledge.KnowledgeCategory;
import io.crewscope.domain.knowledge.KnowledgeEntryVersion;
import io.crewscope.domain.knowledge.distiller.KnowledgeDistillerTemplate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pins the two hand-written representations of the knowledge-distiller@1 output schema —
 * the JSON text the model sees (domain template, hash-locked) and the strict map the
 * decoder validates against — to one identical shape. Either side drifting alone would
 * make the model answer to schema A while the runtime rejects it per schema B.
 */
class KnowledgeDistillerSchemaContractTest {

    @Test
    void domainSchemaTextAgreesWithTheApplicationDecodeSpec() {
        String domainText = KnowledgeDistillerTemplate.outputSchema();

        // The text twin carries the same bounds, keys, strictness and enum order.
        assertTrue(domainText.contains("\"additionalProperties\":false"), domainText);
        assertTrue(domainText.contains("\"required\":["), domainText);
        assertTrue(domainText.contains("\"maxLength\":" + KnowledgeEntryVersion.MAX_TITLE_LENGTH),
                domainText);
        assertTrue(
                domainText.contains("\"maxLength\":" + KnowledgeEntryVersion.MAX_CONTENT_LENGTH),
                domainText);
        for (KnowledgeCategory category : KnowledgeCategory.values()) {
            assertTrue(domainText.contains("\"" + category.name() + "\""), domainText);
        }

        Map<String, Object> decode = KnowledgeDistillerStructuredOutputSpecs.DISTILLED_DRAFT
                .strictJsonSchema().orElseThrow();
        assertEquals("object", decode.get("type"));
        assertEquals(Boolean.FALSE, decode.get("additionalProperties"));

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) decode.get("properties");
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) decode.get("required");
        assertEquals(List.of("title", "content", "suggestedCategory"), required);

        @SuppressWarnings("unchecked")
        Map<String, Object> title = (Map<String, Object>) properties.get("title");
        assertEquals("string", title.get("type"));
        assertEquals(KnowledgeEntryVersion.MAX_TITLE_LENGTH, title.get("maxLength"));
        assertEquals(1, title.get("minLength"));

        @SuppressWarnings("unchecked")
        Map<String, Object> content = (Map<String, Object>) properties.get("content");
        assertEquals(KnowledgeEntryVersion.MAX_CONTENT_LENGTH, content.get("maxLength"));

        @SuppressWarnings("unchecked")
        Map<String, Object> suggested =
                (Map<String, Object>) properties.get("suggestedCategory");
        assertEquals(
                List.copyOf(java.util.Arrays.stream(KnowledgeCategory.values())
                        .map(Enum::name).toList()),
                suggested.get("enum"));
    }
}
