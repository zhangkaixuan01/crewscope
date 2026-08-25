package io.crewscope.domain.collaboration;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;

/** Stable identity of one exact OpenAPI member verification proof. */
public record LarkMemberVerificationProofId(UUID value) implements AggregateId {
    public LarkMemberVerificationProofId {
        value = AggregateId.requireValue(value, "LarkMemberVerificationProofId");
    }

    public static LarkMemberVerificationProofId generate() {
        return new LarkMemberVerificationProofId(AggregateId.generateValue());
    }
}
