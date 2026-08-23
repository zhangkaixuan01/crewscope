package io.crewscope.domain.action;

import io.crewscope.domain.provider.ConnectionId;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.task.TaskFactHash;
import java.util.Objects;

/** Connection-scoped digest used to deduplicate webhook deliveries and active queries. */
public record ExternalObservationKey(TaskFactHash value) {

    public static final int MAX_SOURCE_ID_LENGTH = 500;

    public ExternalObservationKey {
        value = Objects.requireNonNull(value, "value");
    }

    public static ExternalObservationKey derive(
            ConnectionId connectionId, ExternalResultSource source, String sourceEventId) {
        if (sourceEventId == null
                || sourceEventId.isBlank()
                || sourceEventId.strip().length() > MAX_SOURCE_ID_LENGTH) {
            throw new DomainValidationException(
                    "externalObservation.sourceEventId",
                    "must be non-blank and within the size limit");
        }
        return new ExternalObservationKey(new ActionCanonicalEncoder("external-observation-v1")
                .add(Objects.requireNonNull(connectionId, "connectionId").toString())
                .add(Objects.requireNonNull(source, "source").name())
                .add(sourceEventId.strip())
                .digest());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
