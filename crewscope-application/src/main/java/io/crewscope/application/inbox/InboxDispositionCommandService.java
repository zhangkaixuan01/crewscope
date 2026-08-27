package io.crewscope.application.inbox;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.command.CommandRequestHash;
import io.crewscope.application.command.CommandReservation;
import io.crewscope.application.command.CommandReservationRequest;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.inbox.InboxDisposition;
import io.crewscope.domain.inbox.InboxItemId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Adds durable idempotency and receipts around the strong-ETag disposition aggregate. */
public final class InboxDispositionCommandService {

    private static final String COMMAND_TYPE = "inbox.change-disposition";

    private final InboxApplicationService authorizationQueries;
    private final InboxDispositionApplicationService dispositions;
    private final CommandReceiptStore receipts;
    private final TransactionExecutor transactions;
    private final TimeProvider timeProvider;

    public InboxDispositionCommandService(
            InboxApplicationService authorizationQueries,
            InboxDispositionApplicationService dispositions,
            CommandReceiptStore receipts,
            TransactionExecutor transactions,
            TimeProvider timeProvider) {
        this.authorizationQueries =
                Objects.requireNonNull(authorizationQueries, "authorizationQueries");
        this.dispositions = Objects.requireNonNull(dispositions, "dispositions");
        this.receipts = Objects.requireNonNull(receipts, "receipts");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
    }

    public CommandExecution<InboxDisposition> change(
            TeamCommandContext context,
            OrganizationId organizationId,
            TeamId teamId,
            InboxItemId inboxItemId,
            ChangeInboxDispositionCommand command) {
        TeamCommandContext trusted = Objects.requireNonNull(context, "context");
        ChangeInboxDispositionCommand requested = Objects.requireNonNull(command, "command");
        // Replays still revalidate current membership and exact item ownership before revealing a
        // receipt, so an actor who left the Team cannot use an old idempotency key as a bypass.
        authorizationQueries.detail(
                trusted.access(), organizationId, teamId, inboxItemId);
        CommandRequestHash hash = CommandRequestHash.sha256(
                COMMAND_TYPE,
                Objects.requireNonNull(organizationId, "organizationId").toString(),
                Objects.requireNonNull(teamId, "teamId").toString(),
                Objects.requireNonNull(inboxItemId, "inboxItemId").toString(),
                requested.targetStatus().name(),
                Long.toString(requested.expectedVersion()));
        Optional<CommandReceipt> completed = receipts.findCompleted(
                organizationId, trusted.idempotencyKey(), COMMAND_TYPE, hash);
        if (completed.isPresent()) {
            return CommandExecution.replayed(completed.orElseThrow());
        }
        return transactions.required(() -> execute(
                trusted, organizationId, teamId, inboxItemId, requested, hash));
    }

    private CommandExecution<InboxDisposition> execute(
            TeamCommandContext context,
            OrganizationId organizationId,
            TeamId teamId,
            InboxItemId inboxItemId,
            ChangeInboxDispositionCommand command,
            CommandRequestHash hash) {
        UtcTimestamp now = timeProvider.now();
        UUID commandId = UUID.randomUUID();
        CommandReservation reservation = receipts.reserve(new CommandReservationRequest(
                organizationId,
                context.idempotencyKey(),
                COMMAND_TYPE,
                hash,
                commandId,
                context.correlationId(),
                now));
        if (!reservation.acquired()) {
            return CommandExecution.replayed(reservation.receipt().orElseThrow());
        }
        InboxDisposition disposition = dispositions.change(
                context.access(), organizationId, teamId, inboxItemId, command);
        // Disposition is a Generation-independent command fact; its stable receipt identity is
        // derived from the command and committed aggregate version without exposing source data.
        UUID factId = UUID.nameUUIDFromBytes(("crewscope:inbox-disposition-command:v1:"
                        + commandId + ":" + disposition.version())
                .getBytes(StandardCharsets.UTF_8));
        CommandReceipt receipt = new CommandReceipt(
                commandId, factId, disposition.version(), context.correlationId());
        receipts.complete(organizationId, context.idempotencyKey(), receipt, now);
        return CommandExecution.completed(disposition, receipt);
    }
}
