package io.crewscope.domain.agent;

import io.crewscope.domain.model.ModelConnectionOwnerType;
import io.crewscope.domain.policy.PolicyPackReference;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;
import java.util.Optional;

/** Append-only Organization or Team default for one template and execution scope. */
public final class AgentModelDefault {

    private final AgentModelDefaultScope scope;
    private final AgentTemplateVersion templateVersion;
    private final AgentTemplateHash templateContentHash;
    private final AgentExecutionScope executionScope;
    private final AgentModelDefaultRevision revision;
    private final Optional<AgentModelDefaultRevision> previousRevision;
    private final AgentDirectModelBinding modelBinding;
    private final PolicyPackReference policyPack;
    private final AgentConfigurationHash contentHash;
    private final AuditMetadata audit;

    private AgentModelDefault(
            AgentTemplateDefinition template,
            AgentModelDefaultScope scope,
            AgentExecutionScope executionScope,
            AgentModelDefaultRevision revision,
            Optional<AgentModelDefaultRevision> previousRevision,
            AgentDirectModelBinding modelBinding,
            PolicyPackReference policyPack,
            AuditMetadata audit,
            AgentConfigurationHash expectedContentHash,
            boolean requireActiveTemplate) {
        AgentTemplateDefinition requiredTemplate = Objects.requireNonNull(template, "template");
        this.scope = requireScope(requiredTemplate, scope);
        this.templateVersion = requiredTemplate.templateVersion();
        this.templateContentHash = requiredTemplate.contentHash();
        this.executionScope = Objects.requireNonNull(executionScope, "executionScope");
        if (requireActiveTemplate && requiredTemplate.status() != AgentTemplateStatus.ACTIVE) {
            throw new DomainValidationException(
                    "agentModelDefault.templateVersion",
                    "must reference an ACTIVE Agent template version");
        }
        if (!requiredTemplate.allowedExecutionScopes().contains(this.executionScope)) {
            throw new DomainValidationException(
                    "agentModelDefault.executionScope",
                    "must be allowed by the Agent template");
        }
        this.revision = Objects.requireNonNull(revision, "revision");
        this.previousRevision = requirePreviousRevision(this.revision, previousRevision);
        this.modelBinding = Objects.requireNonNull(modelBinding, "modelBinding");
        requireDefaultSelection(this.scope, this.modelBinding.primary());
        this.modelBinding.fallback().ifPresent(selection ->
                requireDefaultSelection(this.scope, selection));
        this.policyPack = Objects.requireNonNull(policyPack, "policyPack");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.contentHash = calculateContentHash();
        if (expectedContentHash != null && !expectedContentHash.equals(this.contentHash)) {
            throw new DomainValidationException(
                    "agentModelDefault.contentHash",
                    "must match the canonical Agent model default");
        }
    }

    /** Publishes revision one of an explicit non-USER model default. */
    public static AgentModelDefault publishInitial(
            AgentTemplateDefinition template,
            AgentModelDefaultScope scope,
            AgentExecutionScope executionScope,
            AgentDirectModelBinding modelBinding,
            PolicyPackReference policyPack,
            PrincipalId actor,
            UtcTimestamp occurredAt) {
        return new AgentModelDefault(
                template,
                scope,
                executionScope,
                new AgentModelDefaultRevision(1),
                Optional.empty(),
                modelBinding,
                policyPack,
                AuditMetadata.createdBy(actor, occurredAt),
                null,
                true);
    }

    /** Appends the next default revision without changing its scope or template coordinate. */
    public AgentModelDefault publishNext(
            AgentTemplateDefinition template,
            AgentDirectModelBinding nextModelBinding,
            PolicyPackReference nextPolicyPack,
            PrincipalId actor,
            UtcTimestamp occurredAt) {
        requireSameTemplate(template);
        return new AgentModelDefault(
                template,
                scope,
                executionScope,
                revision.next(),
                Optional.of(revision),
                nextModelBinding,
                nextPolicyPack,
                AuditMetadata.createdBy(actor, occurredAt),
                null,
                true);
    }

    /** Reconstitutes a historical default and verifies its canonical content hash. */
    public static AgentModelDefault reconstitute(
            AgentTemplateDefinition template,
            AgentModelDefaultScope scope,
            AgentExecutionScope executionScope,
            AgentModelDefaultRevision revision,
            Optional<AgentModelDefaultRevision> previousRevision,
            AgentDirectModelBinding modelBinding,
            PolicyPackReference policyPack,
            AgentConfigurationHash contentHash,
            AuditMetadata audit) {
        return new AgentModelDefault(
                template,
                scope,
                executionScope,
                revision,
                previousRevision,
                modelBinding,
                policyPack,
                audit,
                Objects.requireNonNull(contentHash, "contentHash"),
                false);
    }

    private void requireSameTemplate(AgentTemplateDefinition template) {
        AgentTemplateDefinition required = Objects.requireNonNull(template, "template");
        if (!templateVersion.equals(required.templateVersion())
                || !templateContentHash.equals(required.contentHash())) {
            throw new DomainValidationException(
                    "agentModelDefault.templateVersion",
                    "must preserve the exact template version and content hash");
        }
    }

    private AgentConfigurationHash calculateContentHash() {
        StringBuilder canonical = new StringBuilder("agent-model-default-v1");
        AgentConfigurationHash.append(canonical, scope.organizationId().toString());
        AgentConfigurationHash.append(
                canonical, scope.teamId().map(Object::toString).orElse("team:none"));
        AgentConfigurationHash.append(canonical, templateVersion.toString());
        AgentConfigurationHash.append(canonical, templateContentHash.toString());
        AgentConfigurationHash.append(canonical, executionScope.name());
        AgentConfigurationHash.append(canonical, Long.toString(revision.value()));
        AgentConfigurationHash.append(
                canonical,
                previousRevision.map(value -> Long.toString(value.value()))
                        .orElse("previous:none"));
        modelBinding.appendCanonical(canonical);
        AgentConfigurationHash.append(canonical, policyPack.id().toString());
        AgentConfigurationHash.append(canonical, Long.toString(policyPack.version()));
        return AgentConfigurationHash.sha256(canonical.toString());
    }

    private static AgentModelDefaultScope requireScope(
            AgentTemplateDefinition template, AgentModelDefaultScope scope) {
        AgentModelDefaultScope required = Objects.requireNonNull(scope, "scope");
        if (!required.organizationId().equals(template.publisherScope().organizationId())) {
            throw new DomainValidationException(
                    "agentModelDefault.scope", "must match the template Organization");
        }
        template.publisherScope().teamId().ifPresent(publisherTeam -> {
            if (required.teamId().filter(publisherTeam::equals).isEmpty()) {
                throw new DomainValidationException(
                        "agentModelDefault.scope",
                        "must stay inside the Team-published template boundary");
            }
        });
        return required;
    }

    private static void requireDefaultSelection(
            AgentModelDefaultScope scope, AgentModelSelection selection) {
        if (!scope.organizationId().equals(selection.organizationId())
                || selection.connectionOwner().type() == ModelConnectionOwnerType.USER) {
            throw new DomainValidationException(
                    "agentModelDefault.modelBinding",
                    "must use a non-USER Connection in the default Organization");
        }
        if (scope.teamId().isEmpty()) {
            if (selection.connectionOwner().type() != ModelConnectionOwnerType.ORGANIZATION) {
                throw new DomainValidationException(
                        "agentModelDefault.modelBinding",
                        "an Organization default must use an Organization Connection");
            }
        } else if (selection.connectionOwner().type() == ModelConnectionOwnerType.TEAM
                && selection.connectionOwner().teamId()
                        .filter(scope.teamId().orElseThrow()::equals)
                        .isEmpty()) {
            throw new DomainValidationException(
                    "agentModelDefault.modelBinding",
                    "a Team default must use its own Team or Organization Connection");
        }
    }

    private static Optional<AgentModelDefaultRevision> requirePreviousRevision(
            AgentModelDefaultRevision current,
            Optional<AgentModelDefaultRevision> previousRevision) {
        Optional<AgentModelDefaultRevision> required = Objects.requireNonNull(
                previousRevision, "previousRevision");
        if (current.value() == 1 && required.isPresent()) {
            throw new DomainValidationException(
                    "agentModelDefault.previousRevision", "must be empty for revision one");
        }
        if (current.value() > 1) {
            AgentModelDefaultRevision previous = required.orElseThrow(() ->
                    new DomainValidationException(
                            "agentModelDefault.previousRevision",
                            "is required after revision one"));
            if (previous.value() != current.value() - 1) {
                throw new DomainValidationException(
                        "agentModelDefault.previousRevision",
                        "must reference the immediately preceding revision");
            }
        }
        return required;
    }

    public AgentModelDefaultScope scope() { return scope; }

    public AgentTemplateVersion templateVersion() { return templateVersion; }

    public AgentTemplateHash templateContentHash() { return templateContentHash; }

    public AgentExecutionScope executionScope() { return executionScope; }

    public AgentModelDefaultRevision revision() { return revision; }

    public Optional<AgentModelDefaultRevision> previousRevision() { return previousRevision; }

    public AgentDirectModelBinding modelBinding() { return modelBinding; }

    public PolicyPackReference policyPack() { return policyPack; }

    public AgentConfigurationHash contentHash() { return contentHash; }

    public AuditMetadata audit() { return audit; }
}
