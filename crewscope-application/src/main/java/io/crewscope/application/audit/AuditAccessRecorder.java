package io.crewscope.application.audit;

/** Append-only security sink for Audit Explorer query and export attempts. */
public interface AuditAccessRecorder {

    void record(AuditAccessRecord record);

    static AuditAccessRecorder noOp() {
        return ignored -> { };
    }
}
