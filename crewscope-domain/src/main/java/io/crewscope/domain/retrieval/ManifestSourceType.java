package io.crewscope.domain.retrieval;

/**
 * Origin of one injected prompt fragment. The four values also name the four prompt
 * layers used by budget accounting and trimming (M10-S01 §3.3).
 */
public enum ManifestSourceType {
    KNOWLEDGE_ENTRY,
    REPOSITORY_CHUNK,
    MEMORY_PREFERENCE,
    SKILL_INSTRUCTION
}
