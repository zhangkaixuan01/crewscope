package io.crewscope.application.agent;

import io.crewscope.application.review.output.ReviewerStructuredOutputSpecs;
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
import io.crewscope.domain.agent.AgentToolKey;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.teamobserver.TeamObserverTemplate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.ObjectMapper;

/**
 * Platform catalog for the four product-level Agent entry points.
 *
 * <p>Templates are persisted through the normal append-only repository so their hashes and audit
 * coordinates are produced by the same domain code as administrator-published templates. Existing
 * rows are never replaced; this makes startup repair safe after a local data reset.
 */
public final class DefaultAgentTemplateCatalogInitializer implements AgentTemplateCatalogInitializer {

    private final AgentTemplateRepository templates;
    private final ObjectMapper objectMapper;

    public DefaultAgentTemplateCatalogInitializer(
            AgentTemplateRepository templates, ObjectMapper objectMapper) {
        this.templates = Objects.requireNonNull(templates, "templates");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public void initialize(OrganizationId organizationId, PrincipalId actor, UtcTimestamp occurredAt) {
        OrganizationId organization = Objects.requireNonNull(organizationId, "organizationId");
        PrincipalId principal = Objects.requireNonNull(actor, "actor");
        UtcTimestamp time = Objects.requireNonNull(occurredAt, "occurredAt");
        AgentTemplatePublisherScope scope = AgentTemplatePublisherScope.organization(organization);
        builtIns(organization, principal, time)
                .forEach(template -> initializeTemplate(scope, template));
    }

    private void initializeTemplate(
            AgentTemplatePublisherScope scope, AgentTemplateDefinition expected) {
        Optional<AgentTemplateDefinition> committed =
                templates.findByVersion(scope, expected.templateVersion());
        if (committed.isPresent()) {
            requireSameBuiltIn(expected, committed.orElseThrow());
            return;
        }
        try {
            templates.append(expected);
        } catch (DomainValidationException conflict) {
            // Startup and Team creation can initialize the same Organization concurrently. The
            // repository serializes the append, so a losing writer succeeds only when it can
            // prove that the exact immutable built-in definition was committed by the winner.
            Optional<AgentTemplateDefinition> winner =
                    templates.findByVersion(scope, expected.templateVersion());
            if (winner.isPresent()
                    && winner.orElseThrow().contentHash().equals(expected.contentHash())) {
                return;
            }
            throw conflict;
        }
    }

    private static void requireSameBuiltIn(
            AgentTemplateDefinition expected, AgentTemplateDefinition committed) {
        if (!committed.contentHash().equals(expected.contentHash())) {
            throw new DomainValidationException(
                    "agentTemplate.contentHash",
                    "the committed built-in " + expected.templateVersion()
                            + " differs from the platform definition");
        }
    }

    private List<AgentTemplateDefinition> builtIns(
            OrganizationId organization, PrincipalId actor, UtcTimestamp occurredAt) {
        AgentTemplatePublisherScope scope = AgentTemplatePublisherScope.organization(organization);
        Set<AgentOwnershipType> userAndTeam = Set.of(AgentOwnershipType.USER, AgentOwnershipType.TEAM);
        Set<AgentExecutionScope> personalAndTeam =
                Set.of(AgentExecutionScope.PERSONAL, AgentExecutionScope.TEAM);
        Set<AgentConfigurableSlot> memberSlots = Set.of(
                AgentConfigurableSlot.DISPLAY_NAME,
                AgentConfigurableSlot.SUPPLEMENTAL_INSTRUCTIONS,
                AgentConfigurableSlot.APPROVED_SKILLS,
                AgentConfigurableSlot.MODEL_BINDING,
                AgentConfigurableSlot.OUTPUT_PREFERENCE);
        Set<AgentConfigurableSlot> adminSlots = Set.of(AgentConfigurableSlot.BUDGET);

        AgentTemplateDefinition personal = AgentTemplateDefinition.publishInitial(
                scope, new AgentTemplateKey("personal-assistant"), AgentRuntimeRole.PERSONAL_ASSISTANT,
                Set.of(AgentOwnershipType.USER), personalAndTeam,
                capabilities(Set.of("conversation.orchestrate")),
                policy("Assist the member and orchestrate approved CrewScope tasks.", Set.of(), Set.of(),
                        Optional.empty(), memberSlots, adminSlots), actor, occurredAt);
        AgentTemplateDefinition coding = AgentTemplateDefinition.publishInitial(
                scope, new AgentTemplateKey("coding"), AgentRuntimeRole.SPECIALIST,
                userAndTeam, personalAndTeam,
                capabilities(Set.of("source-code.change"), "model.tool-calling"),
                policy("Perform the approved coding task within the bounded repository workspace.",
                        Set.of("repository.read", "repository.write"), Set.of("coding-baseline"),
                        Optional.empty(), memberSlots, adminSlots), actor, occurredAt);
        AgentTemplateDefinition reviewer = AgentTemplateDefinition.publishInitial(
                scope, new AgentTemplateKey("reviewer"), AgentRuntimeRole.SPECIALIST,
                userAndTeam, personalAndTeam,
                capabilities(Set.of("source-code.review"), "model.structured-output"),
                policy("Review the supplied immutable evidence and return only structured findings.",
                        Set.of(), Set.of(), Optional.of(reviewerSchema()), memberSlots, adminSlots),
                actor, occurredAt);
        AgentTemplateDefinition team = AgentTemplateDefinition.publishInitial(
                scope, new AgentTemplateKey("team-coordinator"), AgentRuntimeRole.TEAM_COORDINATOR,
                Set.of(AgentOwnershipType.TEAM), Set.of(AgentExecutionScope.TEAM),
                capabilities(Set.of("team.coordinate"), "model.tool-calling"),
                policy("Coordinate member-visible Team work using approved platform capabilities.",
                        Set.of(), Set.of(), Optional.empty(), Set.of(AgentConfigurableSlot.MODEL_BINDING),
                        adminSlots), actor, occurredAt);
        AgentTemplateDefinition observer =
                TeamObserverTemplate.create(organization, actor, occurredAt);
        return List.of(personal, coding, reviewer, team, observer);
    }

    private String reviewerSchema() {
        try {
            return objectMapper.writeValueAsString(
                    ReviewerStructuredOutputSpecs.REVIEW_FINDING_LIST.strictJsonSchema().orElseThrow());
        } catch (Exception failure) {
            throw new IllegalStateException("Unable to serialize the built-in Reviewer schema", failure);
        }
    }

    private static AgentTemplateCapabilities capabilities(Set<String> declared, String... required) {
        Set<AgentTemplateCapability> declaredCapabilities = declared.stream()
                .map(AgentTemplateCapability::new).collect(java.util.stream.Collectors.toSet());
        Set<AgentTemplateCapability> requiredCapabilities = java.util.Arrays.stream(required)
                .map(AgentTemplateCapability::new).collect(java.util.stream.Collectors.toSet());
        return AgentTemplateCapabilities.define(declaredCapabilities, requiredCapabilities);
    }

    private static AgentTemplatePolicy policy(
            String prompt, Set<String> tools, Set<String> skills, Optional<String> schema,
            Set<AgentConfigurableSlot> member, Set<AgentConfigurableSlot> administrator) {
        return AgentTemplatePolicy.define(
                prompt,
                tools.stream().map(AgentToolKey::new).collect(java.util.stream.Collectors.toSet()),
                skills, schema, member, administrator);
    }
}
