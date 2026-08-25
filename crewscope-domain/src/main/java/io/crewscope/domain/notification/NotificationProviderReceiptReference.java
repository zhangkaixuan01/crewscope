package io.crewscope.domain.notification;

import io.crewscope.domain.task.TaskFactHash;
import java.util.Objects;

/** Hashed provider receipt reference safe for persistence and logs. */
public record NotificationProviderReceiptReference(TaskFactHash safeHash) {
    public NotificationProviderReceiptReference {
        safeHash = Objects.requireNonNull(safeHash, "safeHash");
    }

    public static NotificationProviderReceiptReference hashed(String providerReference) {
        return new NotificationProviderReceiptReference(
                TaskFactHash.sha256(Objects.requireNonNull(providerReference, "providerReference")));
    }
}
