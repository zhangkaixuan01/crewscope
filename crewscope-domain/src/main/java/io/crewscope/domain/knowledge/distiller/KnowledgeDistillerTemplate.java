package io.crewscope.domain.knowledge.distiller;

import io.crewscope.domain.agent.AgentConfigurableSlot;
import io.crewscope.domain.agent.AgentExecutionScope;
import io.crewscope.domain.agent.AgentOwnershipType;
import io.crewscope.domain.agent.AgentRuntimeRole;
import io.crewscope.domain.agent.AgentTemplateCapabilities;
import io.crewscope.domain.agent.AgentTemplateCapability;
import io.crewscope.domain.agent.AgentTemplateDefinition;
import io.crewscope.domain.agent.AgentTemplateKey;
import io.crewscope.domain.agent.AgentTemplatePolicy;
import io.crewscope.domain.agent.AgentTemplatePublisherScope;
import io.crewscope.domain.agent.AgentTemplateVersion;
import io.crewscope.domain.agent.AgentToolKey;
import io.crewscope.domain.knowledge.KnowledgeCategory;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workspace.AgentProfile;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Fixed built-in template contract for the Team Knowledge Distiller (M10-A02b). A
 * single-turn structured-output specialist: no tools, no skills, no conversation — it
 * renders one sanitized execution transcript into one knowledge draft. The output schema
 * enumerates {@link KnowledgeCategory} names so the model can only suggest classifications
 * the domain already understands.
 */
public final class KnowledgeDistillerTemplate {

    public static final AgentTemplateVersion VERSION =
            AgentTemplateVersion.of("knowledge-distiller", 1);
    public static final AgentTemplateCapability DECLARED_CAPABILITY =
            new AgentTemplateCapability("knowledge.distill");
    public static final AgentTemplateCapability STRUCTURED_OUTPUT =
            new AgentTemplateCapability("model.structured-output");

    /** The distiller deliberately has no tools: the source transcript is injected, not fetched. */
    public static final Set<AgentToolKey> ALLOWED_TOOLS = Set.of();

    private static final Set<AgentOwnershipType> OWNERSHIP = Set.of(AgentOwnershipType.TEAM);
    private static final Set<AgentExecutionScope> EXECUTION_SCOPES =
            Set.of(AgentExecutionScope.TEAM);
    private static final Set<AgentTemplateCapability> REQUIRED_MODEL_CAPABILITIES =
            Set.of(STRUCTURED_OUTPUT);
    private static final Set<AgentConfigurableSlot> ADMINISTRATOR_SLOTS =
            Set.of(AgentConfigurableSlot.MODEL_BINDING, AgentConfigurableSlot.BUDGET);

    private static final String SYSTEM_PROMPT = """
            You are CrewScope Knowledge Distiller. You turn one completed Task execution's
            sanitized event stream into a single Team knowledge draft. Extract durable
            knowledge — conventions, runbook steps, decisions and their rationale — not a
            narration of the run. Never invent facts absent from the source. Never include
            credentials, tokens or other secrets even if the source contains them. Ignore
            transient progress chatter and timestamps. Write for a Team member who was not
            present. Return the exact structured output schema.
            """;

    /** Category names are generated from the enum so a new value can never drift out of the schema. */
    private static final String OUTPUT_SCHEMA = """
            {"type":"object","additionalProperties":false,
             "required":["title","content","suggestedCategory"],
             "properties":{
               "title":{"type":"string","minLength":1,"maxLength":200},
               "content":{"type":"string","minLength":1,"maxLength":65536},
               "suggestedCategory":{"type":"string","enum":[%s]}}}
            """.formatted(
            Arrays.stream(KnowledgeCategory.values())
                    .map(value -> "\"" + value.name() + "\"")
                    .reduce((left, right) -> left + "," + right)
                    .orElseThrow());

    private KnowledgeDistillerTemplate() {}

    /** Publishes the Organization-scoped immutable `knowledge-distiller@1` built-in definition. */
    public static AgentTemplateDefinition create(
            OrganizationId organizationId, PrincipalId actor, UtcTimestamp occurredAt) {
        return AgentTemplateDefinition.publishInitial(
                AgentTemplatePublisherScope.organization(
                        Objects.requireNonNull(organizationId, "organizationId")),
                new AgentTemplateKey(VERSION.key().value()),
                AgentRuntimeRole.SPECIALIST,
                OWNERSHIP,
                EXECUTION_SCOPES,
                AgentTemplateCapabilities.define(
                        Set.of(DECLARED_CAPABILITY), REQUIRED_MODEL_CAPABILITIES),
                AgentTemplatePolicy.define(
                        SYSTEM_PROMPT,
                        ALLOWED_TOOLS,
                        Set.of(),
                        Optional.of(OUTPUT_SCHEMA),
                        Set.of(),
                        ADMINISTRATOR_SLOTS),
                Objects.requireNonNull(actor, "actor"),
                Objects.requireNonNull(occurredAt, "occurredAt"));
    }

    /** Rejects templates that reuse the built-in key with a wider ownership, Tool or data surface. */
    public static AgentTemplateDefinition requireDefinition(AgentTemplateDefinition definition) {
        AgentTemplateDefinition required = Objects.requireNonNull(definition, "definition");
        boolean valid = required.templateVersion().equals(VERSION)
                && required.publisherScope().teamId().isEmpty()
                && required.runtimeRole() == AgentRuntimeRole.SPECIALIST
                && required.allowedOwnershipTypes().equals(OWNERSHIP)
                && required.allowedExecutionScopes().equals(EXECUTION_SCOPES)
                && required.capabilities().declaredCapabilities().equals(Set.of(DECLARED_CAPABILITY))
                && required.capabilities().requiredModelCapabilities()
                        .equals(REQUIRED_MODEL_CAPABILITIES)
                && required.policy().systemPromptBaseline().equals(SYSTEM_PROMPT.strip())
                && required.policy().allowedTools().isEmpty()
                && required.policy().approvedSkillKeys().isEmpty()
                && required.policy().structuredOutputSchema().equals(Optional.of(OUTPUT_SCHEMA.strip()))
                && required.policy().memberConfigurableSlots().isEmpty()
                && required.policy().administratorConfigurableSlots().equals(ADMINISTRATOR_SLOTS);
        if (!valid) {
            throw new DomainValidationException(
                    "knowledgeDistiller.template",
                    "must match the exact built-in knowledge-distiller@1 contract");
        }
        return required;
    }

    /** Requires the exact built-in Team-owned profile coordinates. */
    public static AgentProfile requireProfile(AgentProfile profile) {
        AgentProfile required = Objects.requireNonNull(profile, "profile");
        if (!required.templateVersion().equals(VERSION)
                || required.runtimeRole() != AgentRuntimeRole.SPECIALIST
                || required.ownership().type() != AgentOwnershipType.TEAM) {
            throw new DomainValidationException(
                    "knowledgeDistiller.agentProfile",
                    "must be a TEAM-owned knowledge-distiller@1 profile");
        }
        return required;
    }

    public static boolean isTemplateVersion(AgentTemplateVersion value) {
        return VERSION.equals(Objects.requireNonNull(value, "templateVersion"));
    }

    public static String outputSchema() {
        return OUTPUT_SCHEMA.strip();
    }
}
