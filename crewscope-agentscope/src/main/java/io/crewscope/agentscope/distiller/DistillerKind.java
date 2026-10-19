package io.crewscope.agentscope.distiller;

/**
 * The two built-in distiller flavours sharing one runtime shape (M13-C02): each kind pins
 * its own deterministic Team identity namespace, AgentScope state-slot namespace and label,
 * and selects its own security-chain kind downstream.
 */
public enum DistillerKind {

    KNOWLEDGE(
            "Knowledge Distiller",
            "knowledge-distiller",
            "io.crewscope/knowledge-distiller-state/v1/"),
    SKILL(
            "Skill Distiller",
            "skill-distiller",
            "io.crewscope/skill-distiller-state/v1/");

    private final String label;
    private final String sessionNamespace;
    private final String stateTopicPrefix;

    DistillerKind(String label, String sessionNamespace, String stateTopicPrefix) {
        this.label = label;
        this.sessionNamespace = sessionNamespace;
        this.stateTopicPrefix = stateTopicPrefix;
    }

    public String label() {
        return label;
    }

    public String sessionNamespace() {
        return sessionNamespace;
    }

    public String stateTopicPrefix() {
        return stateTopicPrefix;
    }
}
