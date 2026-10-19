package io.crewscope.domain.knowledge.distiller;

import io.crewscope.domain.agent.AgentTemplateCapability;
import io.crewscope.domain.agent.AgentTemplateDefinition;
import io.crewscope.domain.agent.AgentTemplateVersion;
import io.crewscope.domain.agent.AgentToolKey;
import io.crewscope.domain.distiller.DistillerTemplateContract;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workspace.AgentProfile;
import java.util.Set;

/**
 * Fixed built-in template contract for the Team Knowledge Distiller (M10-A02b). The
 * knowledge-distiller@1 identity, schema and baselines are supplied to the shared
 * {@link DistillerTemplateContract} (M13-C03); this class keeps the historical
 * static API.
 */
public final class KnowledgeDistillerTemplate {

    private static final DistillerTemplateContract CONTRACT = DistillerTemplateContract.knowledge();

    public static final AgentTemplateVersion VERSION = CONTRACT.version();
    public static final AgentTemplateCapability DECLARED_CAPABILITY = CONTRACT.declaredCapability();
    public static final AgentTemplateCapability STRUCTURED_OUTPUT =
            DistillerTemplateContract.STRUCTURED_OUTPUT;
    public static final Set<AgentToolKey> ALLOWED_TOOLS = DistillerTemplateContract.ALLOWED_TOOLS;

    private KnowledgeDistillerTemplate() {}

    /** Publishes the Organization-scoped immutable `knowledge-distiller@1` built-in definition. */
    public static AgentTemplateDefinition create(
            OrganizationId organizationId, PrincipalId actor, UtcTimestamp occurredAt) {
        return CONTRACT.create(organizationId, actor, occurredAt);
    }

    /** Rejects templates that reuse the built-in key with a wider ownership, Tool or data surface. */
    public static AgentTemplateDefinition requireDefinition(AgentTemplateDefinition definition) {
        return CONTRACT.requireDefinition(definition);
    }

    /** Requires the exact built-in Team-owned profile coordinates. */
    public static AgentProfile requireProfile(AgentProfile profile) {
        return CONTRACT.requireProfile(profile);
    }

    public static boolean isTemplateVersion(AgentTemplateVersion value) {
        return CONTRACT.isTemplateVersion(value);
    }

    public static String outputSchema() {
        return CONTRACT.outputSchema();
    }
}
