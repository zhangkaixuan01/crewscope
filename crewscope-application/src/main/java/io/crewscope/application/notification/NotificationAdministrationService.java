package io.crewscope.application.notification;

import io.crewscope.application.collaboration.LarkMappingAdministration;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamMemberRepository;
import io.crewscope.domain.notification.NotificationDeliveryId;
import io.crewscope.domain.notification.NotificationPreference;
import io.crewscope.domain.notification.NotificationRedeliveryCommandId;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamMemberId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Team-administrator boundary for fixed notification settings, history and redelivery. */
public final class NotificationAdministrationService {

    public static final int MAX_LIMIT = 200;

    private final LarkMappingAdministration administration;
    private final NotificationAdministrationRepository repository;
    private final NotificationPlanningApplicationService planning;
    private final TeamMemberRepository members;
    private final TimeProvider timeProvider;

    public NotificationAdministrationService(
            LarkMappingAdministration administration,
            NotificationAdministrationRepository repository,
            NotificationPlanningApplicationService planning,
            TeamMemberRepository members,
            TimeProvider timeProvider) {
        this.administration = Objects.requireNonNull(administration, "administration");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.planning = Objects.requireNonNull(planning, "planning");
        this.members = Objects.requireNonNull(members, "members");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
    }

    public NotificationPreference preference(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            TeamMemberId memberId) {
        authorize(context, organizationId, teamId);
        return repository.findPreference(organizationId, teamId, memberId)
                .orElseGet(() -> defaultPreference(memberId));
    }

    public NotificationPreference updatePreference(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            TeamMemberId memberId,
            UpdateNotificationPreferenceCommand command) {
        UtcTimestamp now = authorize(context, organizationId, teamId);
        UpdateNotificationPreferenceCommand required = Objects.requireNonNull(command, "command");
        requireFutureMutedUntil(required, now);
        NotificationPreference preference = new NotificationPreference(
                memberId,
                required.enabled(),
                required.enabledItemTypes(),
                required.mutedUntil(),
                required.expectedVersion() + 1);
        return repository.savePreference(
                organizationId,
                teamId,
                preference,
                required.expectedVersion(),
                context.actor(),
                now);
    }

    /** Reads the caller's own preference; membership itself is the whole authorization. */
    public NotificationPreference selfPreference(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId) {
        TeamMember member = requireSelfMember(context, organizationId, teamId);
        return repository.findPreference(organizationId, teamId, member.id())
                .orElseGet(() -> defaultPreference(member.id()));
    }

    /** Updates the caller's own preference under the same strong-version contract as admins. */
    public NotificationPreference updateSelfPreference(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            UpdateNotificationPreferenceCommand command) {
        Objects.requireNonNull(context, "context");
        UpdateNotificationPreferenceCommand required = Objects.requireNonNull(command, "command");
        UtcTimestamp now = timeProvider.now();
        requireFutureMutedUntil(required, now);
        TeamMember member = requireSelfMember(context, organizationId, teamId);
        NotificationPreference preference = new NotificationPreference(
                member.id(),
                required.enabled(),
                required.enabledItemTypes(),
                required.mutedUntil(),
                required.expectedVersion() + 1);
        return repository.savePreference(
                organizationId,
                teamId,
                preference,
                required.expectedVersion(),
                context.actor(),
                now);
    }

    public List<NotificationTemplateView> templates(
            TeamAccessContext context, OrganizationId organizationId, TeamId teamId) {
        authorize(context, organizationId, teamId);
        return repository.listTemplates();
    }

    public NotificationDeliveryPage deliveries(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            NotificationDeliveryFilter filter,
            Optional<NotificationDeliveryCursor> after,
            int limit) {
        authorize(context, organizationId, teamId);
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("Notification delivery limit is outside bounds");
        }
        return repository.findDeliveries(
                organizationId, teamId, filter, after, limit);
    }

    public NotificationDeliveryView delivery(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            NotificationDeliveryId deliveryId) {
        authorize(context, organizationId, teamId);
        return repository.findDelivery(organizationId, teamId, deliveryId)
                .orElseThrow(() -> new AggregateNotFoundException(
                        "NotificationDelivery", deliveryId));
    }

    public NotificationRedeliveryRecord redeliver(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            NotificationDeliveryId deliveryId,
            long expectedVersion,
            NotificationRedeliveryCommandId commandId) {
        authorize(context, organizationId, teamId);
        NotificationDeliveryView original = repository.findDelivery(
                        organizationId, teamId, deliveryId)
                .orElseThrow(() -> new AggregateNotFoundException(
                        "NotificationDelivery", deliveryId));
        if (original.version() != expectedVersion) {
            throw new OptimisticLockConflictException(
                    "NotificationDelivery", deliveryId, expectedVersion, original.version());
        }
        return planning.redeliverScheduled(
                commandId, organizationId, deliveryId, expectedVersion);
    }

    public void requireAdministrator(
            TeamAccessContext context, OrganizationId organizationId, TeamId teamId) {
        authorize(context, organizationId, teamId);
    }

    private UtcTimestamp authorize(
            TeamAccessContext context, OrganizationId organizationId, TeamId teamId) {
        TeamAccessContext access = Objects.requireNonNull(context, "context");
        UtcTimestamp now = timeProvider.now();
        administration.requireProviderAdministrator(
                organizationId, teamId, access.actor(), now);
        return now;
    }

    /** Self access derives the member from the authenticated principal; nothing is client-set. */
    private TeamMember requireSelfMember(
            TeamAccessContext context, OrganizationId organizationId, TeamId teamId) {
        TeamAccessContext access = Objects.requireNonNull(context, "context");
        return members
                .findByTeamAndUserPrincipalId(
                        Objects.requireNonNull(organizationId, "organizationId"),
                        Objects.requireNonNull(teamId, "teamId"),
                        access.actor().id())
                .filter(TeamMember::canParticipate)
                .orElseThrow(() -> new PolicyDeniedException(
                        "manage the own notification preference"));
    }

    private static void requireFutureMutedUntil(
            UpdateNotificationPreferenceCommand required, UtcTimestamp now) {
        required.mutedUntil().ifPresent(until -> {
            if (until.compareTo(now) <= 0) {
                throw new DomainValidationException(
                        "notificationPreference.mutedUntil", "must be in the future");
            }
        });
    }

    private static NotificationPreference defaultPreference(TeamMemberId memberId) {
        return new NotificationPreference(
                memberId, true, java.util.EnumSet.allOf(
                        io.crewscope.domain.inbox.InboxItemType.class),
                Optional.empty(), 0);
    }
}
