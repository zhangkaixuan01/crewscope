package io.crewscope.application.knowledge.output;

/**
 * Java shape of the {@code knowledge-distillation/v1} structured output: exactly the
 * draft fields the knowledge-distiller@1 model call may return, nothing else.
 */
public record DistilledDraftV1(String title, String content, String suggestedCategory) {

    public static final String SCHEMA_VERSION = "knowledge-distillation/v1";
}
