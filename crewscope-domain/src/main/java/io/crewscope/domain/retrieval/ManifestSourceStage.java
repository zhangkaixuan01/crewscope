package io.crewscope.domain.retrieval;

/**
 * Whether a manifest reference was still a candidate when the budget cut it, or was
 * actually injected into the prompt (M10-S01 §3.3). Declared references claimed by the
 * model are receipts compared against INJECTED rows; their persisted shape was
 * delivered with M10-I02c ({@link ManifestSourceKey} plus the claimed-reference
 * receipt table).
 */
public enum ManifestSourceStage {
    CANDIDATE,
    INJECTED
}
