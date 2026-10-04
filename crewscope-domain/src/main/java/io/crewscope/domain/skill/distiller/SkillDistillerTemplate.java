package io.crewscope.domain.skill.distiller;

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
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workspace.AgentProfile;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Fixed built-in template contract for the Team Skill Distiller (M10-A03b). A
 * single-turn structured-output specialist mirroring the Knowledge Distiller: no tools,
 * no skills, no conversation — it renders one completed execution's sanitized transcript
 * into one Skill draft body. The stable {@code skillKey} is chosen by the requesting
 * member and assembled server-side, never by the model, so the output schema carries
 * only the human-facing description and the markdown body.
 */
public final class SkillDistillerTemplate {

    public static final AgentTemplateVersion VERSION =
            AgentTemplateVersion.of("skill-distiller", 1);
    public static final AgentTemplateCapability DECLARED_CAPABILITY =
            new AgentTemplateCapability("skill.distill");
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

    private static final String OUTPUT_SCHEMA = """
            {"type":"object","additionalProperties":false,
             "required":["description","body"],
             "properties":{
               "description":{"type":"string","minLength":1,"maxLength":200},
               "body":{"type":"string","minLength":1,"maxLength":65536}}}
            """;

    private SkillDistillerTemplate() {}

    /** Publishes the Organization-scoped immutable `skill-distiller@1` built-in definition. */
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
                    "skillDistiller.template",
                    "must match the exact built-in skill-distiller@1 contract");
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
                    "skillDistiller.agentProfile",
                    "must be a TEAM-owned skill-distiller@1 profile");
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
