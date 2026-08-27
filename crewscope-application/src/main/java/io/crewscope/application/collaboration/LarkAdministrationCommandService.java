package io.crewscope.application.collaboration;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.command.CommandRequestHash;
import io.crewscope.application.command.CommandReservation;
import io.crewscope.application.command.CommandReservationRequest;
import io.crewscope.application.notification.NotificationAdministrationService;
import io.crewscope.application.notification.NotificationRedeliveryRecord;
import io.crewscope.application.notification.UpdateNotificationPreferenceCommand;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.collaboration.LarkMemberMapping;
import io.crewscope.domain.collaboration.LarkMemberMappingId;
import io.crewscope.domain.collaboration.LarkMemberMappingTerminalReason;
import io.crewscope.domain.collaboration.LarkMemberVerificationProof;
import io.crewscope.domain.collaboration.LarkMemberVerificationProofId;
import io.crewscope.domain.collaboration.LarkOpenId;
import io.crewscope.domain.notification.NotificationDeliveryId;
import io.crewscope.domain.notification.NotificationPreference;
import io.crewscope.domain.notification.NotificationRedeliveryCommandId;
import io.crewscope.domain.provider.ProviderBindingId;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamMemberId;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/** Durable idempotency and receipts for Lark mapping, preference and redelivery commands. */
public final class LarkAdministrationCommandService {

    private final LarkMappingAdministration administration;
    private final LarkMemberMappingApplicationService mappings;
    private final NotificationAdministrationService notifications;
    private final CommandReceiptStore receipts;
    private final TransactionExecutor transactions;
    private final TimeProvider timeProvider;

    public LarkAdministrationCommandService(
            LarkMappingAdministration administration,
            LarkMemberMappingApplicationService mappings,
            NotificationAdministrationService notifications,
            CommandReceiptStore receipts,
            TransactionExecutor transactions,
            TimeProvider timeProvider) {
        this.administration = Objects.requireNonNull(administration, "administration");
        this.mappings = Objects.requireNonNull(mappings, "mappings");
        this.notifications = Objects.requireNonNull(notifications, "notifications");
        this.receipts = Objects.requireNonNull(receipts, "receipts");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
    }

    public CommandExecution<LarkMemberVerificationProof> verifyMember(
            TeamCommandContext context,
            OrganizationId organizationId,
            TeamId teamId,
            ProviderBindingId bindingId,
            long expectedBindingVersion,
            LarkOpenId openId) {
        TeamCommandContext trusted = authorize(context, organizationId, teamId);
        CommandRequestHash hash = CommandRequestHash.sha256(
                "lark.verify-member",
                organizationId.toString(), teamId.toString(), bindingId.toString(),
                Long.toString(expectedBindingVersion), openId.value());
        return execute(
                trusted,
                organizationId,
                "lark.verify-member",
                hash,
                ignored -> {
                    LarkMemberVerificationProof proof = mappings.verifyMember(
                            new VerifyLarkMemberCommand(
                                    organizationId, teamId, bindingId, openId,
                                    trusted.access().actor()));
                    if (proof.providerBindingVersion() != expectedBindingVersion) {
                        throw new OptimisticLockConflictException(
                                "ProviderBinding",
                                bindingId,
                                expectedBindingVersion,
                                proof.providerBindingVersion());
                    }
                    return proof;
                },
                proof -> proof.id().value(),
                ignored -> 0L);
    }

    public CommandExecution<LarkMemberMapping> confirmMapping(
            TeamCommandContext context,
            OrganizationId organizationId,
            TeamId teamId,
            TeamMemberId memberId,
            ProviderBindingId bindingId,
            LarkMemberVerificationProofId proofId) {
        TeamCommandContext trusted = authorize(context, organizationId, teamId);
        CommandRequestHash hash = CommandRequestHash.sha256(
                "lark.confirm-mapping",
                organizationId.toString(), teamId.toString(), memberId.toString(),
                bindingId.toString(), proofId.value().toString());
        return execute(
                trusted,
                organizationId,
                "lark.confirm-mapping",
                hash,
                ignored -> mappings.confirmMapping(new ConfirmLarkMemberMappingCommand(
                        organizationId,
                        teamId,
                        memberId,
                        bindingId,
                        proofId,
                        trusted.access().actor())),
                mapping -> mapping.id().value(),
                LarkMemberMapping::version);
    }

    public CommandExecution<LarkMemberMapping> revokeMapping(
            TeamCommandContext context,
            OrganizationId organizationId,
            TeamId teamId,
            LarkMemberMappingId mappingId,
            long expectedVersion,
            LarkMemberMappingTerminalReason reason) {
        TeamCommandContext trusted = authorize(context, organizationId, teamId);
        CommandRequestHash hash = CommandRequestHash.sha256(
                "lark.revoke-mapping",
                organizationId.toString(), teamId.toString(), mappingId.toString(),
                Long.toString(expectedVersion), reason.name());
        return execute(
                trusted,
                organizationId,
                "lark.revoke-mapping",
                hash,
                ignored -> mappings.revokeMapping(new RevokeLarkMemberMappingCommand(
                        organizationId,
                        mappingId,
                        expectedVersion,
                        reason,
                        trusted.access().actor())),
                mapping -> mapping.id().value(),
                LarkMemberMapping::version);
    }

    public CommandExecution<NotificationPreference> updatePreference(
            TeamCommandContext context,
            OrganizationId organizationId,
            TeamId teamId,
            TeamMemberId memberId,
            UpdateNotificationPreferenceCommand command) {
        TeamCommandContext trusted = authorize(context, organizationId, teamId);
        UpdateNotificationPreferenceCommand required = Objects.requireNonNull(command, "command");
        CommandRequestHash hash = CommandRequestHash.sha256(
                "lark.update-notification-preference",
                organizationId.toString(), teamId.toString(), memberId.toString(),
                Boolean.toString(required.enabled()),
                required.enabledItemTypes().stream().map(Enum::name).sorted()
                        .collect(java.util.stream.Collectors.joining(",")),
                required.mutedUntil().map(Object::toString).orElse(""),
                Long.toString(required.expectedVersion()));
        return execute(
                trusted,
                organizationId,
                "lark.update-notification-preference",
                hash,
                ignored -> notifications.updatePreference(
                        trusted.access(), organizationId, teamId, memberId, required),
                preference -> memberId.value(),
                NotificationPreference::version);
    }

    public CommandExecution<NotificationRedeliveryRecord> redeliver(
            TeamCommandContext context,
            OrganizationId organizationId,
            TeamId teamId,
            NotificationDeliveryId deliveryId,
            long expectedVersion) {
        TeamCommandContext trusted = authorize(context, organizationId, teamId);
        CommandRequestHash hash = CommandRequestHash.sha256(
                "lark.redeliver-notification",
                organizationId.toString(), teamId.toString(), deliveryId.toString(),
                Long.toString(expectedVersion));
        return execute(
                trusted,
                organizationId,
                "lark.redeliver-notification",
                hash,
                commandId -> notifications.redeliver(
                        trusted.access(),
                        organizationId,
                        teamId,
                        deliveryId,
                        expectedVersion,
                        new NotificationRedeliveryCommandId(commandId)),
                record -> record.plan().delivery().id().value(),
                record -> record.plan().delivery().version());
    }

    private TeamCommandContext authorize(
            TeamCommandContext context, OrganizationId organizationId, TeamId teamId) {
        TeamCommandContext trusted = Objects.requireNonNull(context, "context");
        administration.requireProviderAdministrator(
                organizationId, teamId, trusted.access().actor(), timeProvider.now());
        return trusted;
    }

    private <T> CommandExecution<T> execute(
            TeamCommandContext context,
            OrganizationId organizationId,
            String commandType,
            CommandRequestHash hash,
            Function<UUID, T> action,
            Function<T, UUID> factId,
            Function<T, Long> version) {
        Optional<CommandReceipt> completed = receipts.findCompleted(
                organizationId, context.idempotencyKey(), commandType, hash);
        if (completed.isPresent()) {
            return CommandExecution.replayed(completed.orElseThrow());
        }
        return transactions.required(() -> {
            UtcTimestamp now = timeProvider.now();
            UUID commandId = UUID.randomUUID();
            CommandReservation reservation = receipts.reserve(new CommandReservationRequest(
                    organizationId,
                    context.idempotencyKey(),
                    commandType,
                    hash,
                    commandId,
                    context.correlationId(),
                    now));
            if (!reservation.acquired()) {
                return CommandExecution.replayed(reservation.receipt().orElseThrow());
            }
            T result = Objects.requireNonNull(action.apply(commandId), "command result");
            CommandReceipt receipt = new CommandReceipt(
                    commandId,
                    Objects.requireNonNull(factId.apply(result), "factId"),
                    Objects.requireNonNull(version.apply(result), "version"),
                    context.correlationId());
            receipts.complete(organizationId, context.idempotencyKey(), receipt, timeProvider.now());
            return CommandExecution.completed(result, receipt);
        });
    }

}
