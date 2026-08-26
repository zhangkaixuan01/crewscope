package io.crewscope.application.collaboration;

import io.crewscope.domain.collaboration.LarkExternalMemberKey;
import io.crewscope.domain.collaboration.LarkInternalMemberKey;
import io.crewscope.domain.collaboration.LarkMemberMapping;
import io.crewscope.domain.collaboration.LarkMemberMappingId;
import io.crewscope.domain.shared.id.OrganizationId;
import java.util.Optional;

/** Persistence Port with partial unique constraints for both active mapping identities. */
public interface LarkMemberMappingRepository {

    Optional<LarkMemberMapping> findById(
            OrganizationId organizationId, LarkMemberMappingId id);

    Optional<LarkMemberMapping> findActiveByInternalKey(LarkInternalMemberKey key);

    Optional<LarkMemberMapping> findActiveByExternalKey(LarkExternalMemberKey key);

    /** Lists one exact Team using descending updated-at/ID keyset ordering. */
    default LarkMemberMappingPage findPage(LarkMemberMappingPageRequest request) {
        throw new UnsupportedOperationException("Lark mapping pagination is not implemented");
    }

    /** Atomically enforces one active mapping per internal key and external key. */
    LarkMemberMapping createActive(LarkMemberMapping mapping);

    /** Atomically terminates the exact old row and inserts its reconfirmed replacement. */
    LarkMemberMapping replaceActive(
            LarkMemberMapping terminatedMapping, LarkMemberMapping replacementMapping);

    LarkMemberMapping update(LarkMemberMapping mapping);
}
