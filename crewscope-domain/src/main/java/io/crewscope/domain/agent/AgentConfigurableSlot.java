package io.crewscope.domain.agent;

/** Server-recognized Agent configuration fields; platform-fixed fields never appear here. */
public enum AgentConfigurableSlot {
    DISPLAY_NAME,
    DESCRIPTION,
    SUPPLEMENTAL_INSTRUCTIONS,
    APPROVED_SKILLS,
    KNOWLEDGE_SCOPE,
    MODEL_BINDING,
    PROVIDER_BINDING,
    BUDGET,
    OUTPUT_PREFERENCE
}
