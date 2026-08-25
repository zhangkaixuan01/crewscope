package io.crewscope.application.audit;

/** Persistence boundary for scope-complete keyset queries and bounded export reads. */
public interface AuditQueryPort {

    AuditPage find(AuditQuery query);

    AuditExportBatch export(AuditExportRequest request);
}
