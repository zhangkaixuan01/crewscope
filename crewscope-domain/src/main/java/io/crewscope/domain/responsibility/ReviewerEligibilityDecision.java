package io.crewscope.domain.responsibility;

import io.crewscope.domain.policy.PolicyPackReference;
import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Auditable evidence produced when a human Gate Reviewer passes eligibility checks. */
public record ReviewerEligibilityDecision(
        ReviewerEligibilityMode mode,
        Set<ResponsibilityRole> conflictingRoles,
        Optional<PolicyPackReference> policyPack,
        Optional<String> overrideReason) {

    public static final int MAX_OVERRIDE_REASON_LENGTH = 1_000;

    public ReviewerEligibilityDecision {
        mode = Objects.requireNonNull(mode, "mode");
        conflictingRoles = requireConflictingRoles(conflictingRoles);
        policyPack = Objects.requireNonNull(policyPack, "policyPack");
        overrideReason = normalizeReason(overrideReason);
        validateEvidence(mode, conflictingRoles, policyPack, overrideReason);
    }

    /** Records a normal decision with no duty conflict or policy relaxation. */
    public static ReviewerEligibilityDecision strict() {
        return new ReviewerEligibilityDecision(
                ReviewerEligibilityMode.STRICT_SEPARATION,
                Set.of(),
                Optional.empty(),
                Optional.empty());
    }

    /** Records a narrowly scoped single-member override and its immutable PolicyPack evidence. */
    public static ReviewerEligibilityDecision singleMemberOverride(
            Set<ResponsibilityRole> conflictingRoles,
            PolicyPackReference policyPack,
            String reason) {
        return new ReviewerEligibilityDecision(
                ReviewerEligibilityMode.SINGLE_MEMBER_OVERRIDE,
                conflictingRoles,
                Optional.of(Objects.requireNonNull(policyPack, "policyPack")),
                Optional.ofNullable(reason));
    }

    public boolean degraded() {
        return mode == ReviewerEligibilityMode.SINGLE_MEMBER_OVERRIDE;
    }

    private static Set<ResponsibilityRole> requireConflictingRoles(
            Set<ResponsibilityRole> roles) {
        Set<ResponsibilityRole> required = Set.copyOf(Objects.requireNonNull(roles, "roles"));
        if (required.stream().anyMatch(role ->
                role != ResponsibilityRole.OWNER && role != ResponsibilityRole.EXECUTOR)) {
            throw new DomainValidationException(
                    "reviewerEligibilityDecision.conflictingRoles",
                    "can contain only OWNER and EXECUTOR");
        }
        return required.isEmpty()
                ? Set.of()
                : Set.copyOf(EnumSet.copyOf(required));
    }

    private static Optional<String> normalizeReason(Optional<String> reason) {
        Optional<String> required = Objects.requireNonNull(reason, "overrideReason");
        if (required.isEmpty()) {
            return required;
        }
        String normalized = required.orElseThrow().strip();
        if (normalized.isEmpty()) {
            throw new DomainValidationException(
                    "reviewerEligibilityDecision.overrideReason", "must not be blank");
        }
        if (normalized.length() > MAX_OVERRIDE_REASON_LENGTH) {
            throw new DomainValidationException(
                    "reviewerEligibilityDecision.overrideReason",
                    "must contain at most " + MAX_OVERRIDE_REASON_LENGTH + " characters");
        }
        return Optional.of(normalized);
    }

    private static void validateEvidence(
            ReviewerEligibilityMode mode,
            Set<ResponsibilityRole> conflicts,
            Optional<PolicyPackReference> policyPack,
            Optional<String> reason) {
        boolean override = mode == ReviewerEligibilityMode.SINGLE_MEMBER_OVERRIDE;
        if (override && (conflicts.isEmpty() || policyPack.isEmpty() || reason.isEmpty())) {
            throw new DomainValidationException(
                    "reviewerEligibilityDecision",
                    "single-member override requires conflicts, PolicyPack and reason");
        }
        if (!override && (!conflicts.isEmpty() || policyPack.isPresent() || reason.isPresent())) {
            throw new DomainValidationException(
                    "reviewerEligibilityDecision",
                    "strict separation cannot contain override evidence");
        }
    }
}
