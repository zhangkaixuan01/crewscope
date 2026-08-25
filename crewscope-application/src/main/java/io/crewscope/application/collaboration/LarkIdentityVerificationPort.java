package io.crewscope.application.collaboration;

import io.crewscope.domain.collaboration.LarkConnectionAuthorization;
import io.crewscope.domain.collaboration.LarkExternalTenant;
import io.crewscope.domain.collaboration.LarkOpenId;

/** Fixed-operation Connector Port; it exposes no generic URL, method, body or fuzzy lookup. */
public interface LarkIdentityVerificationPort {

    LarkTenantObservation verifyTenant(LarkConnectionAuthorization authorization);

    LarkMemberObservation verifyMember(
            LarkConnectionAuthorization authorization,
            LarkExternalTenant tenant,
            LarkOpenId exactOpenId);
}
