package io.crewscope.domain.agent;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Immutable Prompt, Tool, Skill, Schema and configuration boundary of one template version. */
public final class AgentTemplatePolicy {

    public static final int MAX_SYSTEM_PROMPT_CHARACTERS = 65_536;
    public static final int MAX_SCHEMA_CHARACTERS = 131_072;
    public static final int MAX_SUPPLEMENTAL_INSTRUCTION_CHARACTERS = 16_384;

    private final String systemPromptBaseline;
    private final Set<AgentToolKey> allowedTools;
    private final Set<String> approvedSkillKeys;
    private final Optional<String> structuredOutputSchema;
    private final Optional<AgentTemplateHash> structuredOutputSchemaHash;
    private final Set<AgentConfigurableSlot> memberConfigurableSlots;
    private final Set<AgentConfigurableSlot> administratorConfigurableSlots;
    private final AgentTemplateHash policyHash;

    private AgentTemplatePolicy(
            String systemPromptBaseline,
            Set<AgentToolKey> allowedTools,
            Set<String> approvedSkillKeys,
            Optional<String> structuredOutputSchema,
            Set<AgentConfigurableSlot> memberConfigurableSlots,
            Set<AgentConfigurableSlot> administratorConfigurableSlots,
            AgentTemplateHash expectedHash) {
        this.systemPromptBaseline = requireText(
                systemPromptBaseline,
                "agentTemplate.systemPromptBaseline",
                MAX_SYSTEM_PROMPT_CHARACTERS);
        this.allowedTools = Set.copyOf(Objects.requireNonNull(allowedTools, "allowedTools"));
        this.approvedSkillKeys = normalizeSkillKeys(approvedSkillKeys);
        this.structuredOutputSchema = normalizeSchema(structuredOutputSchema);
        this.structuredOutputSchemaHash = this.structuredOutputSchema
                .map(AgentTemplateHash::sha256);
        this.memberConfigurableSlots = Set.copyOf(Objects.requireNonNull(
                memberConfigurableSlots, "memberConfigurableSlots"));
        this.administratorConfigurableSlots = Set.copyOf(Objects.requireNonNull(
                administratorConfigurableSlots, "administratorConfigurableSlots"));
        this.policyHash = calculateHash();
        if (expectedHash != null && !expectedHash.equals(this.policyHash)) {
            throw new DomainValidationException(
                    "agentTemplate.policyHash", "must match the canonical policy facts");
        }
    }

    public static AgentTemplatePolicy define(
            String systemPromptBaseline,
            Set<AgentToolKey> allowedTools,
            Set<String> approvedSkillKeys,
            Optional<String> structuredOutputSchema,
            Set<AgentConfigurableSlot> memberConfigurableSlots,
            Set<AgentConfigurableSlot> administratorConfigurableSlots) {
        return new AgentTemplatePolicy(
                systemPromptBaseline,
                allowedTools,
                approvedSkillKeys,
                structuredOutputSchema,
                memberConfigurableSlots,
                administratorConfigurableSlots,
                null);
    }

    public static AgentTemplatePolicy reconstitute(
            String systemPromptBaseline,
            Set<AgentToolKey> allowedTools,
            Set<String> approvedSkillKeys,
            Optional<String> structuredOutputSchema,
            Set<AgentConfigurableSlot> memberConfigurableSlots,
            Set<AgentConfigurableSlot> administratorConfigurableSlots,
            AgentTemplateHash policyHash) {
        return new AgentTemplatePolicy(
                systemPromptBaseline,
                allowedTools,
                approvedSkillKeys,
                structuredOutputSchema,
                memberConfigurableSlots,
                administratorConfigurableSlots,
                Objects.requireNonNull(policyHash, "policyHash"));
    }

    /**
     * Validates member-supplied instructions and proves that the requested Tool and Schema surface
     * is no wider than the immutable template policy.
     */
    public AgentTemplateMemberConfiguration resolveMemberConfiguration(
            Optional<String> supplementalInstructions,
            Set<AgentToolKey> requestedTools,
            Optional<String> requestedStructuredOutputSchema) {
        Optional<String> instructions = normalizeSupplementalInstructions(
                supplementalInstructions);
        Set<AgentToolKey> tools = Set.copyOf(
                Objects.requireNonNull(requestedTools, "requestedTools"));
        if (!allowedTools.containsAll(tools)) {
            throw new DomainValidationException(
                    "agentConfiguration.tools",
                    "must not expand the Agent template Tool policy");
        }
        Optional<String> requestedSchema = normalizeSchema(requestedStructuredOutputSchema);
        Optional<AgentTemplateHash> requestedSchemaHash = requestedSchema
                .map(AgentTemplateHash::sha256);
        if (!structuredOutputSchemaHash.equals(requestedSchemaHash)) {
            throw new DomainValidationException(
                    "agentConfiguration.structuredOutputSchema",
                    "must match the exact Agent template Structured Output Schema");
        }
        return new AgentTemplateMemberConfiguration(instructions, tools, requestedSchemaHash);
    }

    public void requireMemberConfigurable(AgentConfigurableSlot slot) {
        AgentConfigurableSlot requiredSlot = Objects.requireNonNull(slot, "slot");
        if (!memberConfigurableSlots.contains(requiredSlot)) {
            throw new DomainValidationException(
                    "agentConfiguration.slot",
                    requiredSlot + " is not member configurable for this template");
        }
    }

    /** Requires a field to be configurable by either the member or an administrator. */
    public void requireConfigurable(AgentConfigurableSlot slot) {
        AgentConfigurableSlot requiredSlot = Objects.requireNonNull(slot, "slot");
        if (!memberConfigurableSlots.contains(requiredSlot)
                && !administratorConfigurableSlots.contains(requiredSlot)) {
            throw new DomainValidationException(
                    "agentConfiguration.slot",
                    requiredSlot + " is fixed by this Agent template");
        }
    }

    private Optional<String> normalizeSupplementalInstructions(Optional<String> value) {
        Optional<String> requiredValue = Objects.requireNonNull(value, "supplementalInstructions")
                .map(String::strip)
                .filter(instructions -> !instructions.isEmpty());
        if (requiredValue.isPresent()) {
            requireMemberConfigurable(AgentConfigurableSlot.SUPPLEMENTAL_INSTRUCTIONS);
            if (requiredValue.orElseThrow().length()
                    > MAX_SUPPLEMENTAL_INSTRUCTION_CHARACTERS) {
                throw new DomainValidationException(
                        "agentConfiguration.supplementalInstructions",
                        "must contain at most "
                                + MAX_SUPPLEMENTAL_INSTRUCTION_CHARACTERS
                                + " characters");
            }
        }
        return requiredValue;
    }

    private AgentTemplateHash calculateHash() {
        StringBuilder canonical = new StringBuilder("agent-template-policy-v1");
        AgentTemplateHash.append(canonical, systemPromptBaseline);
        allowedTools.stream()
                .sorted(Comparator.naturalOrder())
                .forEach(value -> AgentTemplateHash.append(canonical, "tool:" + value));
        approvedSkillKeys.stream()
                .sorted()
                .forEach(value -> AgentTemplateHash.append(canonical, "skill:" + value));
        AgentTemplateHash.append(
                canonical,
                structuredOutputSchemaHash.map(Object::toString).orElse("schema:none"));
        memberConfigurableSlots.stream()
                .sorted(Comparator.comparing(AgentConfigurableSlot::name))
                .forEach(value -> AgentTemplateHash.append(canonical, "member:" + value));
        administratorConfigurableSlots.stream()
                .sorted(Comparator.comparing(AgentConfigurableSlot::name))
                .forEach(value -> AgentTemplateHash.append(canonical, "admin:" + value));
        return AgentTemplateHash.sha256(canonical.toString());
    }

    private static String requireText(String value, String field, int maximumLength) {
        if (value == null || value.isBlank()) {
            throw new DomainValidationException(field, "must not be blank");
        }
        String normalized = value.strip();
        if (normalized.length() > maximumLength) {
            throw new DomainValidationException(
                    field, "must contain at most " + maximumLength + " characters");
        }
        return normalized;
    }

    private static Optional<String> normalizeSchema(Optional<String> value) {
        Optional<String> requiredValue = Objects.requireNonNull(
                value, "structuredOutputSchema");
        if (requiredValue.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(requireText(
                requiredValue.orElseThrow(),
                "agentTemplate.structuredOutputSchema",
                MAX_SCHEMA_CHARACTERS));
    }

    private static Set<String> normalizeSkillKeys(Set<String> values) {
        Set<String> source = Objects.requireNonNull(values, "approvedSkillKeys");
        java.util.HashSet<String> normalized = new java.util.HashSet<>();
        for (String value : source) {
            if (value == null || !value.matches("[a-z][a-z0-9_.-]{0,127}")) {
                throw new DomainValidationException(
                        "agentTemplate.approvedSkillKeys",
                        "must contain lower-case stable Skill keys");
            }
            normalized.add(value);
        }
        return Set.copyOf(normalized);
    }

    public String systemPromptBaseline() {
        return systemPromptBaseline;
    }

    public Set<AgentToolKey> allowedTools() {
        return allowedTools;
    }

    public Set<String> approvedSkillKeys() {
        return approvedSkillKeys;
    }

    public Optional<String> structuredOutputSchema() {
        return structuredOutputSchema;
    }

    public Optional<AgentTemplateHash> structuredOutputSchemaHash() {
        return structuredOutputSchemaHash;
    }

    public Set<AgentConfigurableSlot> memberConfigurableSlots() {
        return memberConfigurableSlots;
    }

    public Set<AgentConfigurableSlot> administratorConfigurableSlots() {
        return administratorConfigurableSlots;
    }

    public AgentTemplateHash policyHash() {
        return policyHash;
    }
}
