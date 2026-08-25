package io.crewscope.application.notification;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.team.TeamMemberId;

/** Verifies that redelivery is requested by the current ACTIVE recipient member. */
public interface NotificationRecipientAuthorization {

    void requireActiveRecipient(
            OrganizationId organizationId, TeamMemberId recipientMemberId, Principal actor);
}
