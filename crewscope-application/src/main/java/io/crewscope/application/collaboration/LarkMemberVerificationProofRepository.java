package io.crewscope.application.collaboration;

import io.crewscope.domain.collaboration.LarkMemberVerificationProof;
import io.crewscope.domain.collaboration.LarkMemberVerificationProofId;
import io.crewscope.domain.shared.id.OrganizationId;
import java.util.Optional;

/** Persistence Port for short-lived exact member verification proofs. */
public interface LarkMemberVerificationProofRepository {

    LarkMemberVerificationProof create(LarkMemberVerificationProof proof);

    Optional<LarkMemberVerificationProof> findById(
            OrganizationId organizationId, LarkMemberVerificationProofId id);
}
