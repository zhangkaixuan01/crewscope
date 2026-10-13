package io.crewscope.application.organization.governance;

/** Append-only security sink for governance-plane actions (download/step/sweep/denial). */
public interface GovernanceAuditRecorder {

    void record(GovernanceAuditRecord record);

    static GovernanceAuditRecorder noOp() {
        return ignored -> { };
    }
}
