package io.crewscope.application.responsibility;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.command.CommandRequestHash;
import io.crewscope.application.command.CommandReservation;
import io.crewscope.application.command.CommandReservationRequest;
import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.event.PendingOutboxEvent;
import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.application.team.TeamMemberRepository;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.responsibility.ActiveOwnerExpectation;
import io.crewscope.domain.responsibility.ResponsibilityAssignment;
import io.crewscope.domain.responsibility.ResponsibilityAssignmentId;
import io.crewscope.domain.responsibility.ResponsibilityConflictException;
import io.crewscope.domain.responsibility.ResponsibilityRole;
import io.crewscope.domain.responsibility.ResponsibilityVersionConflictException;
import io.crewscope.domain.responsibility.event.ResponsibilityHandoverCreated;
import io.crewscope.domain.responsibility.handover.HandoverJobStatus;
import io.crewscope.domain.responsibility.handover.ResponsibilityHandoverItem;
import io.crewscope.domain.responsibility.handover.ResponsibilityHandoverItemId;
import io.crewscope.domain.responsibility.handover.ResponsibilityHandoverJob;
import io.crewscope.domain.responsibility.handover.ResponsibilityHandoverJobId;
import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.event.AggregateReference;
import io.crewscope.domain.shared.event.DomainEventEnvelope;
import io.crewscope.domain.shared.event.EventActor;
import io.crewscope.domain.shared.event.EventActorType;
import io.crewscope.domain.shared.event.EventType;
import io.crewscope.domain.shared.event.SchemaVersion;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.Team;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.team.TeamPermission;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Responsibility handover under ADR-038 §1: queue one member's active assignments of one role as
 * a durable job, then replay the ordinary responsibility commands item by item — each item in its
 * own short transaction under the job creator's authority, so an interrupted run resumes at the
 * first PENDING item and never replays a DONE transfer.
 */
public final class ResponsibilityHandoverApplicationService {

  private static final String CREATE_RESPONSIBILITY_HANDOVER = "CREATE_RESPONSIBILITY_HANDOVER";
  private static final int MAX_ITEMS = 100;
  private static final String HANDOVER_AGGREGATE = "RESPONSIBILITY_HANDOVER_JOB";

  private final ResponsibilityHandoverRepository handoverRepository;
  private final ResponsibilityAssignmentRepository assignmentRepository;
  private final ResponsibilityCommandService commandService;
  private final WorkItemAccessPolicy accessPolicy;
  private final TeamRepository teamRepository;
  private final TeamMemberRepository teamMemberRepository;
  private final TeamMembershipQuery membershipQuery;
  private final PrincipalRepository principalRepository;
  private final DomainEventStore domainEventStore;
  private final OutboxRepository outboxRepository;
  private final CommandReceiptStore receiptStore;
  private final TransactionExecutor transactionExecutor;
  private final TimeProvider timeProvider;

  public ResponsibilityHandoverApplicationService(
      ResponsibilityHandoverRepository handoverRepository,
      ResponsibilityAssignmentRepository assignmentRepository,
      ResponsibilityCommandService commandService,
      WorkItemAccessPolicy accessPolicy,
      TeamRepository teamRepository,
      TeamMemberRepository teamMemberRepository,
      TeamMembershipQuery membershipQuery,
      PrincipalRepository principalRepository,
      DomainEventStore domainEventStore,
      OutboxRepository outboxRepository,
      CommandReceiptStore receiptStore,
      TransactionExecutor transactionExecutor,
      TimeProvider timeProvider) {
    this.handoverRepository = Objects.requireNonNull(handoverRepository, "handoverRepository");
    this.assignmentRepository =
        Objects.requireNonNull(assignmentRepository, "assignmentRepository");
    this.commandService = Objects.requireNonNull(commandService, "commandService");
    this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
    this.teamRepository = Objects.requireNonNull(teamRepository, "teamRepository");
    this.teamMemberRepository =
        Objects.requireNonNull(teamMemberRepository, "teamMemberRepository");
    this.membershipQuery = Objects.requireNonNull(membershipQuery, "membershipQuery");
    this.principalRepository =
        Objects.requireNonNull(principalRepository, "principalRepository");
    this.domainEventStore = Objects.requireNonNull(domainEventStore, "domainEventStore");
    this.outboxRepository = Objects.requireNonNull(outboxRepository, "outboxRepository");
    this.receiptStore = Objects.requireNonNull(receiptStore, "receiptStore");
    this.transactionExecutor =
        Objects.requireNonNull(transactionExecutor, "transactionExecutor");
    this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
  }

  /** One created or replayed handover job together with its queued items. */
  public record HandoverJobCreated(
      ResponsibilityHandoverJob job,
      List<ResponsibilityHandoverItem> items,
      CommandReceipt receipt,
      boolean replayed) {}

  /** One job and its items at their current processing state. */
  public record HandoverJobView(
      ResponsibilityHandoverJob job, List<ResponsibilityHandoverItem> items) {}

  /** Lists the source member's active assignments of the role for the confirmation page. */
  public List<ResponsibilityAssignment> preview(
      TeamCommandContext context,
      TeamId teamId,
      TeamMemberId memberId,
      Optional<ResponsibilityRole> role) {
    TeamCommandContext trusted = requireCommandContext(context);
    TeamId requiredTeamId = Objects.requireNonNull(teamId, "teamId");
    TeamMemberId requiredMemberId = Objects.requireNonNull(memberId, "memberId");
    OrganizationId organizationId = trusted.access().actor().scope().organizationId();
    UtcTimestamp now = timeProvider.now();
    // Read under the Team lock so the confirmation page cannot interleave with a lifecycle
    // command that is moving this member's facts right now.
    requireLockedTeam(organizationId, requiredTeamId);
    requireManageMembers(trusted, organizationId, requiredTeamId, now);
    requireMember(organizationId, requiredTeamId, requiredMemberId);
    return assignmentRepository
        .findActiveByActorMember(organizationId, requiredTeamId, requiredMemberId)
        .stream()
        .filter(value -> role.isEmpty() || value.role() == role.get())
        .toList();
  }

  /**
   * Queues one handover job. Idempotent: replaying the original command key returns the original
   * job instead of queueing a second one.
   */
  public HandoverJobCreated createJob(
      TeamCommandContext context,
      TeamId teamId,
      TeamMemberId sourceMemberId,
      PrincipalId targetPrincipalId,
      ResponsibilityRole role) {
    TeamCommandContext trusted = requireCommandContext(context);
    TeamId requiredTeamId = Objects.requireNonNull(teamId, "teamId");
    TeamMemberId requiredSourceId = Objects.requireNonNull(sourceMemberId, "sourceMemberId");
    PrincipalId requiredTargetId = Objects.requireNonNull(targetPrincipalId, "targetPrincipalId");
    ResponsibilityRole requiredRole = Objects.requireNonNull(role, "role");
    CommandRequestHash hash =
        CommandRequestHash.sha256(
            CREATE_RESPONSIBILITY_HANDOVER,
            trusted.access().actor().id().toString(),
            requiredTeamId.toString(),
            requiredSourceId.toString(),
            requiredTargetId.toString(),
            requiredRole.name(),
            trusted.causationId().map(UUID::toString).orElse(""));
    CommandExecution<HandoverJobCreated> execution =
        execute(
            trusted,
            CREATE_RESPONSIBILITY_HANDOVER,
            hash,
            commandId -> {
              HandoverJobCreated created =
                  createJobInTransaction(
                      trusted,
                      commandId,
                      requiredTeamId,
                      requiredSourceId,
                      requiredTargetId,
                      requiredRole);
              return CommandExecution.completed(created, created.receipt());
            });
    if (execution.replayed()) {
      return replayOriginal(trusted, execution.receipt());
    }
    return execution.result().orElseThrow();
  }

  /**
   * Processes every PENDING item in its own short transaction. Each item replays the ordinary
   * responsibility commands under the job creator's revalidated authority; unexpected failures
   * abort the run and leave the item PENDING for the next attempt.
   */
  public HandoverJobView processJob(
      TeamCommandContext context, TeamId teamId, ResponsibilityHandoverJobId jobId) {
    TeamCommandContext trusted = requireCommandContext(context);
    TeamId requiredTeamId = Objects.requireNonNull(teamId, "teamId");
    ResponsibilityHandoverJobId requiredJobId = Objects.requireNonNull(jobId, "jobId");
    OrganizationId organizationId = trusted.access().actor().scope().organizationId();
    requireManageMembers(trusted, organizationId, requiredTeamId, timeProvider.now());
    ResponsibilityHandoverJob job =
        transactionExecutor.required(
            () -> {
              ResponsibilityHandoverJob locked =
                  requireLockedJob(organizationId, requiredTeamId, requiredJobId);
              if (locked.isTerminal()) {
                return locked;
              }
              return handoverRepository.updateJob(
                  locked.markRunning(trusted.access().actor().id(), timeProvider.now()));
            });
    if (job.isTerminal()) {
      return new HandoverJobView(job, handoverRepository.findItems(organizationId, requiredJobId));
    }
    Principal executor = requireExecutorPrincipal(organizationId, job);
    TeamAccessContext executorAccess = new TeamAccessContext(executor, false);
    for (ResponsibilityHandoverItem item :
        handoverRepository.findItems(organizationId, requiredJobId)) {
      if (!item.isPending()) {
        continue;
      }
      if (cancelledWhileProcessing(organizationId, requiredJobId)) {
        break;
      }
      // processOneItem manages its own per-item transactions (replay+DONE, stopped outcome);
      // wrapping it here would join them and re-encounter the replay's rollback-only mark.
      processOneItem(job, item, executorAccess, trusted.access().actor().id());
    }
    ResponsibilityHandoverJob completed =
        transactionExecutor.required(
            () -> {
              ResponsibilityHandoverJob locked =
                  requireLockedJob(organizationId, requiredTeamId, requiredJobId);
              if (locked.isTerminal()
                  || handoverRepository
                          .findItems(organizationId, requiredJobId)
                          .stream()
                          .anyMatch(ResponsibilityHandoverItem::isPending)) {
                return locked;
              }
              return handoverRepository.updateJob(
                  locked.markCompleted(trusted.access().actor().id(), timeProvider.now()));
            });
    return new HandoverJobView(
        completed, handoverRepository.findItems(organizationId, requiredJobId));
  }

  /** Stops a job that still has unprocessed items; already DONE items are never rolled back. */
  public HandoverJobView cancelJob(
      TeamCommandContext context, TeamId teamId, ResponsibilityHandoverJobId jobId) {
    TeamCommandContext trusted = requireCommandContext(context);
    TeamId requiredTeamId = Objects.requireNonNull(teamId, "teamId");
    ResponsibilityHandoverJobId requiredJobId = Objects.requireNonNull(jobId, "jobId");
    OrganizationId organizationId = trusted.access().actor().scope().organizationId();
    requireManageMembers(trusted, organizationId, requiredTeamId, timeProvider.now());
    return transactionExecutor.required(
        () -> {
          ResponsibilityHandoverJob job =
              requireLockedJob(organizationId, requiredTeamId, requiredJobId);
          if (job.isTerminal()) {
            throw new DomainValidationException(
                "responsibilityHandoverJob.status", "must not be terminal");
          }
          ResponsibilityHandoverJob cancelled =
              handoverRepository.updateJob(
                  job.markCancelled(trusted.access().actor().id(), timeProvider.now()));
          return new HandoverJobView(
              cancelled, handoverRepository.findItems(organizationId, requiredJobId));
        });
  }

  /** Returns one job and its items at their current processing state. */
  public HandoverJobView getJob(
      TeamCommandContext context, TeamId teamId, ResponsibilityHandoverJobId jobId) {
    TeamCommandContext trusted = requireCommandContext(context);
    TeamId requiredTeamId = Objects.requireNonNull(teamId, "teamId");
    ResponsibilityHandoverJobId requiredJobId = Objects.requireNonNull(jobId, "jobId");
    OrganizationId organizationId = trusted.access().actor().scope().organizationId();
    requireManageMembers(trusted, organizationId, requiredTeamId, timeProvider.now());
    ResponsibilityHandoverJob job =
        handoverRepository
            .findJobById(organizationId, requiredJobId)
            .filter(value -> value.teamId().equals(requiredTeamId))
            .orElseThrow(
                () -> new AggregateNotFoundException("ResponsibilityHandoverJob", requiredJobId));
    return new HandoverJobView(job, handoverRepository.findItems(organizationId, requiredJobId));
  }

  private HandoverJobCreated createJobInTransaction(
      TeamCommandContext trusted,
      UUID commandId,
      TeamId teamId,
      TeamMemberId sourceMemberId,
      PrincipalId targetPrincipalId,
      ResponsibilityRole role) {
    UtcTimestamp now = timeProvider.now();
    Principal actor = trusted.access().actor();
    OrganizationId organizationId = actor.scope().organizationId();
    requireLockedTeam(organizationId, teamId);
    requireManageMembers(trusted, organizationId, teamId, now);
    TeamMember source = requireMember(organizationId, teamId, sourceMemberId);
    requireTarget(organizationId, teamId, targetPrincipalId);
    List<ResponsibilityAssignment> sources =
        assignmentRepository
            .findActiveByActorMember(organizationId, teamId, sourceMemberId)
            .stream()
            .filter(value -> value.role() == role)
            .sorted(
                Comparator.comparing(
                    value -> value.workItemId().toString()))
            .toList();
    if (sources.isEmpty()) {
      throw new DomainValidationException(
          "responsibilityHandover.items",
          "the member holds no active assignment for this role");
    }
    if (sources.size() > MAX_ITEMS) {
      throw new DomainValidationException(
          "responsibilityHandover.items", "must not exceed " + MAX_ITEMS + " items");
    }
    for (ResponsibilityAssignment pinned : sources) {
      accessPolicy.requirePermission(
          trusted.access(),
          organizationId,
          teamId,
          pinned.scope().projectId(),
          pinned.workItemId(),
          TeamPermission.RESPONSIBILITY_MANAGE,
          now,
          "manage this WorkItem's responsibilities");
    }
    ResponsibilityHandoverJob job =
        ResponsibilityHandoverJob.create(
            ResponsibilityHandoverJobId.generate(),
            organizationId,
            teamId,
            sourceMemberId,
            targetPrincipalId,
            role,
            trusted.idempotencyKey().value(),
            actor.id(),
            source.authorizationVersion(),
            now);
    List<ResponsibilityHandoverItem> items =
        sources.stream()
            .map(
                value ->
                    ResponsibilityHandoverItem.create(
                        ResponsibilityHandoverItemId.generate(),
                        job.id(),
                        value,
                        actor.id(),
                        now))
            .toList();
    handoverRepository.createJob(job, items);
    return publishCreated(trusted, commandId, job, items);
  }

  /**
   * Transfers one item by replaying the ordinary commands. OWNER uses the atomic replacement with
   * the pinned ABA expectation; every other role releases the source first and then assigns the
   * target. The replay and its DONE outcome commit in one transaction; a failure rolls that
   * transaction back entirely, and the stopped outcome is committed afterwards in its own fresh
   * transaction — a caught exception inside a joined transaction would otherwise leave the
   * transaction rollback-only and fail the outer commit.
   */
  private ResponsibilityHandoverItem processOneItem(
      ResponsibilityHandoverJob job,
      ResponsibilityHandoverItem queued,
      TeamAccessContext executorAccess,
      PrincipalId triggeredBy) {
    ResponsibilityHandoverItem item =
        transactionExecutor.required(
            () ->
                handoverRepository
                    .lockItemById(job.organizationId(), queued.id())
                    .filter(value -> value.jobId().equals(job.id()))
                    .orElseThrow(
                        () ->
                            new AggregateNotFoundException(
                                "ResponsibilityHandoverItem", queued.id())));
    if (!item.isPending()) {
      // A concurrent run already settled this item; unique(jobId, assignmentId) keeps it single.
      return item;
    }
    UtcTimestamp now = timeProvider.now();
    try {
      return transactionExecutor.required(
          () -> {
            ResponsibilityAssignmentId result = transfer(job, item, executorAccess);
            return handoverRepository.updateItem(item.markDone(triggeredBy, result, now));
          });
    } catch (ResponsibilityVersionConflictException conflict) {
      return settleStopped(job, item, triggeredBy, "OWNER_EXPECTATION_STALE", false);
    } catch (SourceChangedException changed) {
      return settleStopped(job, item, triggeredBy, "SOURCE_ASSIGNMENT_CHANGED", false);
    } catch (OptimisticLockConflictException conflict) {
      return settleStopped(job, item, triggeredBy, "ASSIGNMENT_VERSION_STALE", false);
    } catch (ResponsibilityConflictException conflict) {
      return settleStopped(job, item, triggeredBy, "RESPONSIBILITY_SLOT_HELD", false);
    } catch (PolicyDeniedException denied) {
      return settleStopped(job, item, triggeredBy, "RESPONSIBILITY_MANAGE_DENIED", true);
    } catch (DomainValidationException invalid) {
      return settleStopped(job, item, triggeredBy, "TARGET_NOT_ELIGIBLE", true);
    } catch (AggregateNotFoundException missing) {
      return settleStopped(job, item, triggeredBy, "WORK_ITEM_MISSING", true);
    }
  }

  /** Commits one stopped outcome after the failed replay rolled back in its own transaction. */
  private ResponsibilityHandoverItem settleStopped(
      ResponsibilityHandoverJob job,
      ResponsibilityHandoverItem queued,
      PrincipalId triggeredBy,
      String errorCode,
      boolean denied) {
    UtcTimestamp now = timeProvider.now();
    return transactionExecutor.required(
        () -> {
          ResponsibilityHandoverItem locked =
              handoverRepository
                  .lockItemById(job.organizationId(), queued.id())
                  .filter(value -> value.jobId().equals(job.id()))
                  .orElseThrow(
                      () ->
                          new AggregateNotFoundException(
                              "ResponsibilityHandoverItem", queued.id()));
          if (!locked.isPending()) {
            // A concurrent run settled this item while the failed replay was rolling back.
            return locked;
          }
          return handoverRepository.updateItem(
              denied
                  ? locked.markDenied(triggeredBy, errorCode, now)
                  : locked.markConflict(triggeredBy, errorCode, now));
        });
  }

  private ResponsibilityAssignmentId transfer(
      ResponsibilityHandoverJob job,
      ResponsibilityHandoverItem item,
      TeamAccessContext executorAccess) {
    TeamId teamId = item.scope().teamId();
    if (job.role() == ResponsibilityRole.OWNER) {
      CommandExecution<OwnerAssignmentChange> execution =
          commandService.replaceOwner(
              commandContext(job, item, executorAccess, "owner"),
              teamId,
              item.scope().projectId(),
              item.workItemId(),
              new ReplaceOwnerCommand(
                  job.targetPrincipalId(),
                  ActiveOwnerExpectation.at(
                      item.assignmentId(), item.expectedAssignmentVersion())));
      return execution.result().orElseThrow().active().id();
    }
    requireUnchangedSource(job, item);
    commandService.release(
        commandContext(job, item, executorAccess, "release"),
        teamId,
        item.scope().projectId(),
        item.workItemId(),
        item.assignmentId(),
        new ReleaseResponsibilityCommand(item.expectedAssignmentVersion()));
    if (job.role() == ResponsibilityRole.EXECUTOR) {
      return commandService
          .assignExecutor(
              commandContext(job, item, executorAccess, "assign"),
              teamId,
              item.scope().projectId(),
              item.workItemId(),
              new AssignResponsibilityCommand(job.targetPrincipalId()))
          .result()
          .orElseThrow()
          .id();
    }
    Principal target =
        principalRepository
            .findById(job.organizationId(), job.targetPrincipalId())
            .orElseThrow(
                () -> new AggregateNotFoundException("Principal", job.targetPrincipalId()));
    if (target.type() == PrincipalType.SPECIALIST_AGENT) {
      return commandService
          .assignAdvisoryReviewer(
              commandContext(job, item, executorAccess, "assign"),
              teamId,
              item.scope().projectId(),
              item.workItemId(),
              new AssignResponsibilityCommand(job.targetPrincipalId()))
          .result()
          .orElseThrow()
          .id();
    }
    return commandService
        .assignGateReviewer(
            commandContext(job, item, executorAccess, "assign"),
            teamId,
            item.scope().projectId(),
            item.workItemId(),
            new AssignResponsibilityCommand(job.targetPrincipalId()))
        .result()
        .orElseThrow()
        .assignment()
        .id();
  }

  /** Fails as CONFLICT before issuing any command when the pinned source fact already moved. */
  private void requireUnchangedSource(ResponsibilityHandoverJob job, ResponsibilityHandoverItem item) {
    ResponsibilityAssignment source =
        assignmentRepository
            .findById(job.organizationId(), item.assignmentId())
            .orElseThrow(
                () -> new SourceChangedException(item.assignmentId(), item.expectedAssignmentVersion()));
    if (!source.isActive() || source.version() != item.expectedAssignmentVersion()) {
      throw new SourceChangedException(item.assignmentId(), item.expectedAssignmentVersion());
    }
  }

  /** Maps a stale source fact to the CONFLICT item outcome. */
  private static final class SourceChangedException extends RuntimeException {
    SourceChangedException(ResponsibilityAssignmentId assignmentId, long expectedVersion) {
      super("ResponsibilityAssignment %s changed after version %d"
          .formatted(assignmentId, expectedVersion));
    }
  }

  private TeamCommandContext commandContext(
      ResponsibilityHandoverJob job,
      ResponsibilityHandoverItem item,
      TeamAccessContext executorAccess,
      String suffix) {
    return new TeamCommandContext(
        executorAccess,
        new IdempotencyKey("handover:" + job.id() + ":" + item.id() + ":" + suffix),
        job.id().value(),
        Optional.of(job.id().value()));
  }

  private Principal requireExecutorPrincipal(
      OrganizationId organizationId, ResponsibilityHandoverJob job) {
    return principalRepository
        .findById(organizationId, job.createdByPrincipalId())
        .orElseThrow(
            () -> new AggregateNotFoundException("Principal", job.createdByPrincipalId()));
  }

  private void requireManageMembers(
      TeamCommandContext context,
      OrganizationId organizationId,
      TeamId teamId,
      UtcTimestamp now) {
    accessPolicy.requireTeamPermission(
        context.access(),
        organizationId,
        teamId,
        TeamPermission.MEMBER_MANAGE,
        now,
        "manage Team members");
  }

  private Team requireLockedTeam(OrganizationId organizationId, TeamId teamId) {
    if (teamRepository.findUninitializedById(organizationId, teamId).isPresent()) {
      throw new DomainValidationException("team.initializationStatus", "must be READY");
    }
    return teamRepository
        .lockById(organizationId, teamId)
        .orElseThrow(() -> new AggregateNotFoundException("Team", teamId));
  }

  private TeamMember requireMember(OrganizationId organizationId, TeamId teamId, TeamMemberId memberId) {
    TeamMember member =
        teamMemberRepository
            .findById(organizationId, memberId)
            .orElseThrow(() -> new AggregateNotFoundException("TeamMember", memberId));
    if (!member.scope().teamId().equals(teamId)) {
      throw new AggregateNotFoundException("TeamMember", memberId);
    }
    return member;
  }

  /** The target must be an acting USER of this Organization and an active member of the Team. */
  private void requireTarget(OrganizationId organizationId, TeamId teamId, PrincipalId targetId) {
    Principal target =
        principalRepository
            .findById(organizationId, targetId)
            .orElseThrow(() -> new AggregateNotFoundException("Principal", targetId));
    if (target.type() != PrincipalType.USER
        || !target.canAct()
        || !target.scope().organizationId().equals(organizationId)) {
      throw new DomainValidationException(
          "responsibilityHandoverJob.targetPrincipalId",
          "must reference a USER Principal of this Organization");
    }
    boolean participates =
        membershipQuery.findByTeam(organizationId, teamId).stream()
            .filter(TeamMember::canParticipate)
            .anyMatch(member -> member.userPrincipalId().equals(target.id()));
    if (!participates) {
      throw new DomainValidationException(
          "responsibilityHandoverJob.targetPrincipalId",
          "must reference an active member of this Team");
    }
  }

  private ResponsibilityHandoverJob requireLockedJob(
      OrganizationId organizationId, TeamId teamId, ResponsibilityHandoverJobId jobId) {
    return handoverRepository
        .lockJobById(organizationId, jobId)
        .filter(value -> value.teamId().equals(teamId))
        .orElseThrow(
            () -> new AggregateNotFoundException("ResponsibilityHandoverJob", jobId));
  }

  private boolean cancelledWhileProcessing(
      OrganizationId organizationId, ResponsibilityHandoverJobId jobId) {
    return handoverRepository
        .findJobById(organizationId, jobId)
        .filter(job -> job.status() == HandoverJobStatus.CANCELLED)
        .isPresent();
  }

  private HandoverJobCreated replayOriginal(
      TeamCommandContext trusted, CommandReceipt receipt) {
    OrganizationId organizationId = trusted.access().actor().scope().organizationId();
    ResponsibilityHandoverJob job =
        handoverRepository
            .findJobByCommandId(organizationId, trusted.idempotencyKey().value())
            .orElseThrow(
                () ->
                    new DomainValidationException(
                        "responsibilityHandoverJob.commandId",
                        "no job exists for this command key"));
    return new HandoverJobCreated(
        job,
        handoverRepository.findItems(organizationId, job.id()),
        receipt,
        true);
  }

  private HandoverJobCreated publishCreated(
      TeamCommandContext context,
      UUID commandId,
      ResponsibilityHandoverJob job,
      List<ResponsibilityHandoverItem> items) {
    UtcTimestamp occurredAt = timeProvider.now();
    UUID eventId = UUID.randomUUID();
    DomainEventEnvelope<DomainEvent> event =
        new DomainEventEnvelope<>(
            eventId,
            EventType.from("RESPONSIBILITY_HANDOVER_CREATED"),
            SchemaVersion.V1,
            job.organizationId(),
            Optional.of(job.teamId()),
            Optional.empty(),
            AggregateReference.of(HANDOVER_AGGREGATE, job.id()),
            job.version(),
            EventActor.principal(EventActorType.USER, context.access().actor().id()),
            context.correlationId(),
            context.causationId(),
            Optional.of(context.idempotencyKey().value()),
            occurredAt,
            new ResponsibilityHandoverCreated(
                job.id().value(),
                job.sourceMemberId().value(),
                job.targetPrincipalId().value(),
                job.role().name(),
                items.size(),
                job.sourceAuthorizationVersion()));
    domainEventStore.append(event);
    outboxRepository.enqueue(PendingOutboxEvent.fromDomain(UUID.randomUUID(), event));
    CommandReceipt receipt =
        new CommandReceipt(commandId, eventId, job.version(), context.correlationId());
    receiptStore.complete(
        job.organizationId(), context.idempotencyKey(), receipt, occurredAt);
    return new HandoverJobCreated(job, items, receipt, false);
  }

  private static TeamCommandContext requireCommandContext(TeamCommandContext context) {
    return Objects.requireNonNull(context, "context");
  }

  private <T> CommandExecution<T> execute(
      TeamCommandContext context,
      String commandType,
      CommandRequestHash requestHash,
      Function<UUID, CommandExecution<T>> command) {
    return transactionExecutor.required(
        () -> {
          UtcTimestamp now = timeProvider.now();
          UUID commandId = UUID.randomUUID();
          CommandReservation reservation =
              receiptStore.reserve(
                  new CommandReservationRequest(
                      context.access().actor().scope().organizationId(),
                      context.idempotencyKey(),
                      commandType,
                      requestHash,
                      commandId,
                      context.correlationId(),
                      now));
          if (!reservation.acquired()) {
            return CommandExecution.replayed(reservation.receipt().orElseThrow());
          }
          return command.apply(commandId);
        });
  }
}
