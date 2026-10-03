package io.crewscope.application.retrieval;

/**
 * Derived index state of one knowledge entry (M10-I01b): computed from the vector rows
 * and the latest job, never stored as a column — a stored state would drift from the
 * vector rows it summarizes. {@code PENDING} is also the constant answer whenever the
 * index is not assembled at all.
 */
public enum KnowledgeIndexStatus {
    PENDING,
    INDEXED,
    FAILED
}
