package io.crewscope.application.team;

import io.crewscope.domain.team.TeamInvitation;
import java.util.Objects;

/** Successful issuance result whose plaintext token is available only in this initial response. */
public record TeamInvitationIssueResult(TeamInvitation invitation, InvitationToken token) {

    public TeamInvitationIssueResult {
        invitation = Objects.requireNonNull(invitation, "invitation");
        token = Objects.requireNonNull(token, "token");
    }

    /** Reveals the bearer secret only to the trusted creation DTO mapper. */
    public String revealToken() {
        return token.reveal();
    }

    @Override
    public String toString() {
        return "TeamInvitationIssueResult[invitationId="
                + invitation.id()
                + ", token=[REDACTED]]";
    }
}
