package io.crewscope.application.agent;

/** Atomic persistence Port for an Agent Principal and its stable product profile. */
public interface AgentInstanceRepository {

    /** Creates both records or commits neither record. */
    AgentInstance create(AgentInstance instance);

    /** Commits synchronized Principal/Profile lifecycle transitions with optimistic predicates. */
    AgentInstance updateLifecycle(AgentInstance instance);
}
