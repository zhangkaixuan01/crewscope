package io.crewscope.domain.distiller;

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
 * Fixed built-in template contract shared by the Team Knowledge and Skill Distillers
 * (M13-C03). A single-turn structured-output specialist: no tools, no skills, no
 * conversation — it renders one sanitized execution transcript into one draft. The
 * per-kind inputs are the template key, the declared capability, the output schema and
 * the baselines; the ownership, scope, capability and policy invariants are identical by
 * construction.
 */
public final class DistillerTemplateContract {

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

    private static final String KNOWLEDGE_SYSTEM_PROMPT = """
            You are CrewScope Knowledge Distiller. You turn one completed Task execution's
            sanitized event stream into a single Team knowledge draft. Extract durable
            knowledge — conventions, runbook steps, decisions and their rationale — not a
            narration of the run. Never invent facts absent from the source. Never include
            credentials, tokens or other secrets even if the source contains them. Ignore
            transient progress chatter and timestamps. Write for a Team member who was not
            present. Return the exact structured output schema.
            """;

    private static final String SKILL_SYSTEM_PROMPT = """
            You are CrewScope Skill Distiller. You turn one completed Task execution's
            sanitized event stream into a single Team Skill draft body — a reusable,
            self-contained procedure the Team can later load as instructions. Distill
            the repeatable method: when to apply it, the concrete steps, and the checks
            that prove it worked. Never invent facts absent from the source. Never
            include credentials, tokens or other secrets even if the source contains
            them. Ignore transient progress chatter and timestamps. Write imperative
            markdown for a Team member who was not present. Return the exact
            structured output schema.
            """;

    /** Category names are generated from the enum so a new value can never drift out of the schema. */
    private static final String KNOWLEDGE_OUTPUT_SCHEMA = """
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

    private static final String SKILL_OUTPUT_SCHEMA = """
            {"type":"object","additionalProperties":false,
             "required":["description","body"],
             "properties":{
               "description":{"type":"string","minLength":1,"maxLength":200},
               "body":{"type":"string","minLength":1,"maxLength":65536}}}
            """;

    private final AgentTemplateVersion version;
    private final AgentTemplateCapability declaredCapability;
    private final String errorPrefix;
    private final String contractLabel;
    private final String systemPrompt;
    private final String outputSchema;

    private DistillerTemplateContract(
            AgentTemplateVersion version,
            AgentTemplateCapability declaredCapability,
            String errorPrefix,
            String contractLabel,
            String systemPrompt,
            String outputSchema) {
        this.version = version;
        this.declaredCapability = declaredCapability;
        this.errorPrefix = errorPrefix;
        this.contractLabel = contractLabel;
        this.systemPrompt = systemPrompt;
        this.outputSchema = outputSchema;
    }

    public static DistillerTemplateContract knowledge() {
        return new DistillerTemplateContract(
                AgentTemplateVersion.of("knowledge-distiller", 1),
                new AgentTemplateCapability("knowledge.distill"),
                "knowledgeDistiller",
                "knowledge-distiller@1",
                KNOWLEDGE_SYSTEM_PROMPT,
                KNOWLEDGE_OUTPUT_SCHEMA);
    }

    public static DistillerTemplateContract skill() {
        return new DistillerTemplateContract(
                AgentTemplateVersion.of("skill-distiller", 1),
                new AgentTemplateCapability("skill.distill"),
                "skillDistiller",
                "skill-distiller@1",
                SKILL_SYSTEM_PROMPT,
                SKILL_OUTPUT_SCHEMA);
    }

    public AgentTemplateVersion version() {
        return version;
    }

    public AgentTemplateCapability declaredCapability() {
        return declaredCapability;
    }

    /** Publishes the Organization-scoped immutable built-in definition. */
    public AgentTemplateDefinition create(
            OrganizationId organizationId, PrincipalId actor, UtcTimestamp occurredAt) {
        return AgentTemplateDefinition.publishInitial(
                AgentTemplatePublisherScope.organization(
                        Objects.requireNonNull(organizationId, "organizationId")),
                new AgentTemplateKey(version.key().value()),
                AgentRuntimeRole.SPECIALIST,
                OWNERSHIP,
                EXECUTION_SCOPES,
                AgentTemplateCapabilities.define(
                        Set.of(declaredCapability), REQUIRED_MODEL_CAPABILITIES),
                AgentTemplatePolicy.define(
                        systemPrompt,
                        ALLOWED_TOOLS,
                        Set.of(),
                        Optional.of(outputSchema),
                        Set.of(),
                        ADMINISTRATOR_SLOTS),
                Objects.requireNonNull(actor, "actor"),
                Objects.requireNonNull(occurredAt, "occurredAt"));
    }

    /** Rejects templates that reuse the built-in key with a wider ownership, Tool or data surface. */
    public AgentTemplateDefinition requireDefinition(AgentTemplateDefinition definition) {
        AgentTemplateDefinition required = Objects.requireNonNull(definition, "definition");
        boolean valid = required.templateVersion().equals(version)
                && required.publisherScope().teamId().isEmpty()
                && required.runtimeRole() == AgentRuntimeRole.SPECIALIST
                && required.allowedOwnershipTypes().equals(OWNERSHIP)
                && required.allowedExecutionScopes().equals(EXECUTION_SCOPES)
                && required.capabilities().declaredCapabilities().equals(Set.of(declaredCapability))
                && required.capabilities().requiredModelCapabilities()
                        .equals(REQUIRED_MODEL_CAPABILITIES)
                && required.policy().systemPromptBaseline().equals(systemPrompt.strip())
                && required.policy().allowedTools().isEmpty()
                && required.policy().approvedSkillKeys().isEmpty()
                && required.policy().structuredOutputSchema().equals(Optional.of(outputSchema.strip()))
                && required.policy().memberConfigurableSlots().isEmpty()
                && required.policy().administratorConfigurableSlots().equals(ADMINISTRATOR_SLOTS);
        if (!valid) {
            throw new DomainValidationException(
                    errorPrefix + ".template",
                    "must match the exact built-in " + contractLabel + " contract");
        }
        return required;
    }

    /** Requires the exact built-in Team-owned profile coordinates. */
    public AgentProfile requireProfile(AgentProfile profile) {
        AgentProfile required = Objects.requireNonNull(profile, "profile");
        if (!required.templateVersion().equals(version)
                || required.runtimeRole() != AgentRuntimeRole.SPECIALIST
                || required.ownership().type() != AgentOwnershipType.TEAM) {
            throw new DomainValidationException(
                    errorPrefix + ".agentProfile",
                    "must be a TEAM-owned " + contractLabel + " profile");
        }
        return required;
    }

    public boolean isTemplateVersion(AgentTemplateVersion value) {
        return version.equals(Objects.requireNonNull(value, "templateVersion"));
    }

    public String outputSchema() {
        return outputSchema.strip();
    }
}
