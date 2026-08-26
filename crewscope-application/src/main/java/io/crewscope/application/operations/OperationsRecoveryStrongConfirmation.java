package io.crewscope.application.operations;

import java.util.Objects;

/** Exact phrase bound to the recovery action, target identity and expected version. */
public record OperationsRecoveryStrongConfirmation(
        OperationsRecoveryAction action,
        String targetReferenceHash,
        String phrase) {

    public OperationsRecoveryStrongConfirmation {
        action = Objects.requireNonNull(action, "action");
        targetReferenceHash = requireHash(targetReferenceHash);
        phrase = Objects.requireNonNull(phrase, "phrase").strip();
    }

    public static OperationsRecoveryStrongConfirmation confirm(OperationsRecoveryTarget target) {
        OperationsRecoveryTarget required = Objects.requireNonNull(target, "target");
        return new OperationsRecoveryStrongConfirmation(
                required.action(),
                required.referenceHash(),
                expectedPhrase(required));
    }

    public void require(OperationsRecoveryTarget target) {
        OperationsRecoveryTarget required = Objects.requireNonNull(target, "target");
        if (action != required.action()
                || !targetReferenceHash.equals(required.referenceHash())
                || !phrase.equals(expectedPhrase(required))) {
            throw new IllegalArgumentException(
                    "operations recovery confirmation belongs to another command");
        }
    }

    public static String expectedPhrase(OperationsRecoveryTarget target) {
        OperationsRecoveryTarget required = Objects.requireNonNull(target, "target");
        return "CONFIRM_" + required.action().name() + ":" + required.confirmationToken();
    }

    private static String requireHash(String value) {
        String required = Objects.requireNonNull(value, "targetReferenceHash");
        if (!required.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("targetReferenceHash must be a lowercase SHA-256");
        }
        return required;
    }
}
