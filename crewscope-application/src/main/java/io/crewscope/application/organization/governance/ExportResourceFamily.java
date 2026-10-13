package io.crewscope.application.organization.governance;

/**
 * The v1 frozen resource-family vocabulary of the organization data export (S01 freeze 3.4).
 * Each family carries its batch tier: narrow tables page 1000 rows per batch, wide tables
 * (rich payloads, vectors) page 200 — the audit-export bounded precedent scaled by row width.
 *
 * <p>Vector families join the job only when the caller asked for them; a disabled vector store
 * settles them as SKIPPED/VECTOR_STORE_DISABLED instead of failing the job. Credential families
 * export references only — ids, coordinates, status, timestamps — never secret material.
 */
public enum ExportResourceFamily {
    ORGANIZATION(true),
    TEAM(true),
    TEAM_MEMBER(true),
    TEAM_ROLE(true),
    TEAM_MEMBER_ROLE(true),
    WORKSPACE(true),
    WORK_PROJECT(true),
    WORK_ITEM(false),
    WORK_ITEM_RELATION(false),
    TASK(false),
    TASK_EXECUTION(false),
    RESPONSIBILITY_ASSIGNMENT(true),
    KNOWLEDGE_ENTRY(false),
    KNOWLEDGE_ENTRY_VERSION(false),
    KNOWLEDGE_ENTRY_EMBEDDING(false, true),
    REPOSITORY_INDEX_GENERATION(true),
    REPOSITORY_CHUNK_EMBEDDING(false, true),
    ARTIFACT_REFERENCE(true),
    TASK_CREDENTIAL_GRANT(true),
    CREDENTIAL_SECRET(true),
    LOCAL_CREDENTIAL(true),
    USER_ACCOUNT(true),
    ACCOUNT_ORGANIZATION_BINDING(true),
    AUDIT_EVENT(false);

    /** Rows per batch for the narrow tier. */
    public static final int NARROW_BATCH_ROWS = 1000;

    /** Rows per batch for the wide tier (rich payloads and vector rows). */
    public static final int WIDE_BATCH_ROWS = 200;

    private final boolean narrow;
    private final boolean vector;

    ExportResourceFamily(boolean narrow) {
        this(narrow, false);
    }

    ExportResourceFamily(boolean narrow, boolean vector) {
        this.narrow = narrow;
        this.vector = vector;
    }

    public int rowsPerBatch() {
        return narrow ? NARROW_BATCH_ROWS : WIDE_BATCH_ROWS;
    }

    public boolean isVector() {
        return vector;
    }
}
