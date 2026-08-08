package io.crewscope.domain.responsibility;

import java.util.Objects;
import java.util.Optional;

/** Client expectation for the identity and version of the currently active Owner Assignment. */
public record ActiveOwnerExpectation(
        Optional<ResponsibilityAssignmentId> assignmentId,
        long assignmentVersion) {

    public ActiveOwnerExpectation {
        assignmentId = Objects.requireNonNull(assignmentId, "assignmentId");
        if (assignmentId.isEmpty() && assignmentVersion != -1) {
            throw new IllegalArgumentException(
                    "an absent assignment must use version -1");
        }
        if (assignmentId.isPresent() && assignmentVersion < 0) {
            throw new IllegalArgumentException(
                    "an existing assignment must use a non-negative version");
        }
    }

    public static ActiveOwnerExpectation none() {
        return new ActiveOwnerExpectation(Optional.empty(), -1);
    }

    public static ActiveOwnerExpectation current(ResponsibilityAssignment assignment) {
        ResponsibilityAssignment required = Objects.requireNonNull(assignment, "assignment");
        return at(required.id(), required.version());
    }

    public static ActiveOwnerExpectation at(
            ResponsibilityAssignmentId assignmentId, long version) {
        return new ActiveOwnerExpectation(
                Optional.of(Objects.requireNonNull(assignmentId, "assignmentId")), version);
    }
}
