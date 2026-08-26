package io.crewscope.application.collaboration;

import io.crewscope.domain.collaboration.LarkConnectionAuthorization;
import io.crewscope.domain.shared.id.PrincipalId;

/** Performs one live fixed-tenant query and returns only normalized health evidence. */
public interface LarkProviderHealthPort {

    LarkProviderHealth checkHealth(
            LarkConnectionAuthorization authorization, PrincipalId actor);
}
