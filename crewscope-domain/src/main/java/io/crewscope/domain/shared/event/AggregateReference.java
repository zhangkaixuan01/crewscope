package io.crewscope.domain.shared.event;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;
import java.util.regex.Pattern;

/** Stable reference to the aggregate whose committed version produced an event. */
public record AggregateReference(String type, UUID id) {

    public static final int MAX_TYPE_LENGTH = 100;
    private static final Pattern TYPE_FORMAT = Pattern.compile("[A-Z][A-Z0-9_]*");

    public AggregateReference {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("AggregateReference.type must not be blank");
        }
        type = type.strip();
        if (type.length() > MAX_TYPE_LENGTH || !TYPE_FORMAT.matcher(type).matches()) {
            throw new IllegalArgumentException(
                    "AggregateReference.type must be upper snake case and contain at most "
                            + MAX_TYPE_LENGTH
                            + " characters");
        }
        id = AggregateId.requireValue(id, "AggregateReference.id");
    }

    /** Creates a reference from an existing strongly typed aggregate identifier. */
    public static AggregateReference of(String type, AggregateId id) {
        if (id == null) {
            throw new NullPointerException("id");
        }
        return new AggregateReference(type, id.value());
    }
}
