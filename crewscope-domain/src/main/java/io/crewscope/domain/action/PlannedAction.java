package io.crewscope.domain.action;

import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Immutable planned side effect with typed parameters and closed authorization digest. */
public final class PlannedAction {

    private final PlannedActionId id;
    private final int sequence;
    private final ActionKind kind;
    private final ActionParameters parameters;
    private final List<ActionDependency> dependencies;
    private final ActionAuthoritySnapshot authority;
    private final ActionRiskLevel risk;
    private final UtcTimestamp validUntil;
    private final ActionDigest digest;

    private PlannedAction(
            PlannedActionId id,
            int sequence,
            ActionParameters parameters,
            List<ActionDependency> dependencies,
            ActionAuthoritySnapshot authority,
            ActionRiskLevel risk,
            UtcTimestamp validUntil,
            Optional<ActionDigest> expectedDigest) {
        this.id = Objects.requireNonNull(id, "id");
        if (sequence < 1) {
            throw new DomainValidationException("plannedAction.sequence", "must be positive");
        }
        this.sequence = sequence;
        this.parameters = Objects.requireNonNull(parameters, "parameters");
        this.kind = this.parameters.kind();
        this.dependencies = requireDependencies(dependencies, this.id);
        this.authority = Objects.requireNonNull(authority, "authority");
        this.risk = Objects.requireNonNull(risk, "risk");
        this.validUntil = Objects.requireNonNull(validUntil, "validUntil");
        this.digest = calculateDigest();
        Objects.requireNonNull(expectedDigest, "expectedDigest").ifPresent(expected -> {
            if (!expected.equals(this.digest)) {
                throw new DomainValidationException(
                        "plannedAction.digest", "must match every canonical action fact");
            }
        });
    }

    static PlannedAction plan(
            PlannedActionId id,
            int sequence,
            ActionParameters parameters,
            List<ActionDependency> dependencies,
            ActionAuthoritySnapshot authority,
            ActionRiskLevel risk,
            UtcTimestamp validUntil) {
        return new PlannedAction(
                id, sequence, parameters, dependencies, authority, risk, validUntil, Optional.empty());
    }

    /** Reconstitutes a trusted persisted action and verifies its server-computed Digest. */
    public static PlannedAction reconstitute(
            PlannedActionId id,
            int sequence,
            ActionParameters parameters,
            List<ActionDependency> dependencies,
            ActionAuthoritySnapshot authority,
            ActionRiskLevel risk,
            UtcTimestamp validUntil,
            ActionDigest digest) {
        return new PlannedAction(
                id, sequence, parameters, dependencies, authority, risk, validUntil,
                Optional.of(Objects.requireNonNull(digest, "digest")));
    }

    private ActionDigest calculateDigest() {
        ActionCanonicalEncoder encoder = new ActionCanonicalEncoder("planned-action-v1")
                .add(id.toString())
                .add(Integer.toString(sequence))
                .add(kind.name());
        parameters.appendCanonical(encoder);
        encoder.add(Integer.toString(dependencies.size()));
        dependencies.forEach(dependency -> encoder.add(dependency.predecessorActionId().toString()));
        authority.appendCanonical(encoder);
        encoder.add(risk.name()).add(validUntil.toString());
        return new ActionDigest(encoder.digest());
    }

    private static List<ActionDependency> requireDependencies(
            List<ActionDependency> values, PlannedActionId actionId) {
        List<ActionDependency> required = List.copyOf(Objects.requireNonNull(values, "dependencies"));
        Set<PlannedActionId> unique = new HashSet<>();
        for (ActionDependency dependency : required) {
            ActionDependency value = Objects.requireNonNull(dependency, "dependency");
            if (value.predecessorActionId().equals(actionId) || !unique.add(value.predecessorActionId())) {
                throw new DomainValidationException(
                        "plannedAction.dependencies", "must not self-reference or contain duplicates");
            }
        }
        return required;
    }

    public PlannedActionId id() { return id; }
    public int sequence() { return sequence; }
    public ActionKind kind() { return kind; }
    public ActionParameters parameters() { return parameters; }
    public List<ActionDependency> dependencies() { return dependencies; }
    public ActionAuthoritySnapshot authority() { return authority; }
    public ActionRiskLevel risk() { return risk; }
    public UtcTimestamp validUntil() { return validUntil; }
    public ActionDigest digest() { return digest; }
}
