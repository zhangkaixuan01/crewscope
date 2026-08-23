package io.crewscope.application.action;

import io.crewscope.domain.action.ActionAuthorityFacts;
import io.crewscope.domain.action.ActionAuthoritySnapshot;

/** Rebuilds the current server-owned authority graph for a confirmed Action. */
public interface ActionAuthorityFactsResolver {

    ActionAuthorityFacts resolveCurrent(ActionAuthoritySnapshot confirmedAuthority);
}
