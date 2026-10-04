package io.crewscope.application.skill.output;

/**
 * Java shape of the {@code skill-distillation/v1} structured output: exactly the
 * draft fields the skill-distiller@1 model call may return, nothing else. The
 * stable skill key is command-owned (M10-A03b D4) and therefore never appears here.
 */
public record DistilledSkillDraftV1(String description, String body) {

    public static final String SCHEMA_VERSION = "skill-distillation/v1";
}
