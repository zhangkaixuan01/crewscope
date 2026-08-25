package io.crewscope.domain.teamobserver;

import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workspace.AgentProfile;
import io.crewscope.domain.workspace.AgentProfileId;
import io.crewscope.domain.workspace.AgentProfileStatus;
import java.util.List;
import java.util.Objects;

/** Fixed five-section Team Observer result containing only requester-visible projection summaries. */
public record TeamSummaryResult(
        TeamSummaryRequest request,
        AgentProfileId observerProfileId,
        UtcTimestamp generatedAt,
        List<TeamSummaryEntry> progress,
        List<TeamSummaryEntry> blockers,
        List<TeamSummaryEntry> reviewBacklog,
        List<TeamSummaryEntry> pendingConfirmations,
        List<TeamSummaryEntry> anomalies) {

    public TeamSummaryResult {
        request = Objects.requireNonNull(request, "request");
        observerProfileId = Objects.requireNonNull(observerProfileId, "observerProfileId");
        generatedAt = Objects.requireNonNull(generatedAt, "generatedAt");
        progress = requireEntries(request, progress, TeamSummarySection.PROGRESS);
        blockers = requireEntries(request, blockers, TeamSummarySection.BLOCKERS);
        reviewBacklog = requireEntries(request, reviewBacklog, TeamSummarySection.REVIEW_BACKLOG);
        pendingConfirmations = requireEntries(
                request, pendingConfirmations, TeamSummarySection.PENDING_CONFIRMATIONS);
        anomalies = requireEntries(request, anomalies, TeamSummarySection.ANOMALIES);
    }

    /** Constructs a result only for the active built-in Observer in the exact request Team. */
    public static TeamSummaryResult create(
            TeamSummaryRequest request,
            AgentProfile observerProfile,
            UtcTimestamp generatedAt,
            List<TeamSummaryEntry> progress,
            List<TeamSummaryEntry> blockers,
            List<TeamSummaryEntry> reviewBacklog,
            List<TeamSummaryEntry> pendingConfirmations,
            List<TeamSummaryEntry> anomalies) {
        TeamSummaryRequest requiredRequest = Objects.requireNonNull(request, "request");
        AgentProfile profile = TeamObserverTemplate.requireProfile(observerProfile);
        if (profile.status() != AgentProfileStatus.ACTIVE
                || !profile.scope().organizationId().equals(requiredRequest.organizationId())
                || profile.scope().teamId().filter(requiredRequest.teamId()::equals).isEmpty()) {
            throw new DomainValidationException(
                    "teamSummary.observerProfileId",
                    "must be the active built-in Observer of the request Team");
        }
        return new TeamSummaryResult(
                requiredRequest,
                profile.id(),
                generatedAt,
                progress,
                blockers,
                reviewBacklog,
                pendingConfirmations,
                anomalies);
    }

    private static List<TeamSummaryEntry> requireEntries(
            TeamSummaryRequest request,
            List<TeamSummaryEntry> values,
            TeamSummarySection expectedSection) {
        List<TeamSummaryEntry> required = List.copyOf(
                Objects.requireNonNull(values, expectedSection.name()));
        if (required.size() > request.maxItemsPerSection()) {
            throw new DomainValidationException(
                    "teamSummary." + expectedSection.name().toLowerCase(),
                    "exceeds the requested per-section limit");
        }
        for (TeamSummaryEntry entry : required) {
            if (entry.section() != expectedSection
                    || !entry.organizationId().equals(request.organizationId())
                    || !entry.teamId().equals(request.teamId())
                    || !entry.visibleToMemberId().equals(request.requestingMemberId())) {
                throw new DomainValidationException(
                        "teamSummary." + expectedSection.name().toLowerCase(),
                        "must contain only exact requester-visible Team entries");
            }
        }
        return required;
    }
}
