package io.crewscope.agentscope;

/** Best-effort boundary for model-call logs and metrics; it must not change call semantics. */
@FunctionalInterface
public interface AgentCallObservationSink {

    void record(AgentCallObservationRecord record);

    static AgentCallObservationSink noop() {
        return ignored -> {};
    }
}
