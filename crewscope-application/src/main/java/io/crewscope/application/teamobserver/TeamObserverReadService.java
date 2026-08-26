package io.crewscope.application.teamobserver;

import io.crewscope.application.team.TeamMemberRepository;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.teamobserver.TeamSummaryDataScope;
import io.crewscope.domain.teamobserver.TeamSummaryEntry;
import io.crewscope.domain.teamobserver.TeamSummaryRequest;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Reauthorizes every Observer Tool read and validates the projection result before model exposure. */
public final class TeamObserverReadService {

    private final TeamMemberRepository members;
    private final TeamSummaryProjectionPort projections;

    public TeamObserverReadService(
            TeamMemberRepository members, TeamSummaryProjectionPort projections) {
        this.members = Objects.requireNonNull(members, "members");
        this.projections = Objects.requireNonNull(projections, "projections");
    }

    /** Reads one fixed projection scope after a fresh ACTIVE membership lookup. */
    public List<TeamSummaryEntry> read(
            TeamSummaryRequest request, TeamSummaryDataScope dataScope) {
        TeamSummaryRequest requiredRequest = requireAuthorized(request);
        TeamSummaryDataScope requiredScope = Objects.requireNonNull(dataScope, "dataScope");
        TeamSummaryProjectionQuery query = new TeamSummaryProjectionQuery(
                requiredRequest, requiredScope, requiredRequest.maxItemsPerSection());
        List<TeamSummaryEntry> entries = List.copyOf(Objects.requireNonNull(
                projections.read(query), "TeamSummaryProjectionPort.read result"));
        if (entries.size() > query.limit()) {
            throw new DomainValidationException(
                    "teamSummary.projection", "exceeds the authorized projection read limit");
        }
        Set<EntryIdentity> identities = new HashSet<>();
        for (TeamSummaryEntry entry : entries) {
            TeamSummaryEntry required = Objects.requireNonNull(entry, "teamSummaryEntry");
            if (!required.organizationId().equals(requiredRequest.organizationId())
                    || !required.teamId().equals(requiredRequest.teamId())
                    || !required.visibleToMemberId().equals(requiredRequest.requestingMemberId())
                    || required.dataScope() != requiredScope
                    || !identities.add(new EntryIdentity(
                            required.section(), required.evidencePath()))) {
                throw new DomainValidationException(
                        "teamSummary.projection",
                        "must contain unique entries from the exact authorized Team data scope");
            }
        }
        return entries;
    }

    /** Rechecks membership after model execution before any selected evidence is returned. */
    public TeamSummaryRequest requireAuthorized(TeamSummaryRequest request) {
        TeamSummaryRequest requiredRequest = Objects.requireNonNull(request, "request");
        return requiredRequest.requireAuthorizedMember(members
                .findById(requiredRequest.organizationId(), requiredRequest.requestingMemberId())
                .orElseThrow(() -> new DomainValidationException(
                        "teamSummary.requestingMemberId",
                        "must reference a current active Team member")));
    }

    private record EntryIdentity(
            io.crewscope.domain.teamobserver.TeamSummarySection section,
            String evidencePath) {}
}
