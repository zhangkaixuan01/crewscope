package io.crewscope.application.principal;

import io.crewscope.domain.team.TeamMemberId;

/**
 * Persistence Port for the full-set Team subject directory (M9b-A06).
 *
 * <p>The adapter resolves the authorized candidate set in SQL — ACTIVE Team members plus the Agent
 * profiles visible to the viewing member (the whole Team's history for an AUDIT purpose) — so the
 * page is complete however large the Team is, instead of capped at an in-memory window.
 */
public interface PrincipalDirectoryRepository {

    PrincipalDirectoryPage search(PrincipalDirectoryQuery query, TeamMemberId viewerMemberId);
}
