package io.crewscope.application.identity;

import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.command.CommandRequestHash;
import io.crewscope.application.command.CommandReservation;
import io.crewscope.application.command.CommandReservationRequest;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.event.PendingOutboxEvent;
import io.crewscope.application.team.InvitationTokenDigester;
import io.crewscope.application.team.MemberRoleRepository;
import io.crewscope.application.team.TeamInvitationAcceptancePlan;
import io.crewscope.application.team.TeamInvitationAcceptanceService;
import io.crewscope.application.team.TeamInvitationRepository;
import io.crewscope.application.team.TeamMemberRepository;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.application.team.TeamRoleRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.identity.AccountIdentifierConflictException;
import io.crewscope.domain.identity.AccountOrganizationBinding;
import io.crewscope.domain.identity.AccountOrganizationBindingConflictException;
import io.crewscope.domain.identity.AccountOrganizationBindingId;
import io.crewscope.domain.identity.AccountOrganizationKey;
import io.crewscope.domain.identity.LocalCredentialConflictException;
import io.crewscope.domain.identity.LocalCredentialId;
import io.crewscope.domain.identity.LocalCredentialMetadata;
import io.crewscope.domain.identity.LocalPasswordHash;
import io.crewscope.domain.identity.LoginIdentity;
import io.crewscope.domain.identity.LoginIdentityConflictException;
import io.crewscope.domain.identity.LoginIdentityId;
import io.crewscope.domain.identity.PlatformRole;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.identity.RegistrationMode;
import io.crewscope.domain.identity.UserAccount;
import io.crewscope.domain.identity.UserAccountId;
import io.crewscope.domain.identity.Username;
import io.crewscope.domain.identity.event.AccountRegistrationSource;
import io.crewscope.domain.identity.event.UserAccountRegistered;
import io.crewscope.domain.shared.error.DomainException;
import io.crewscope.domain.shared.error.IdempotencyConflictException;
import io.crewscope.domain.shared.event.AggregateReference;
import io.crewscope.domain.shared.event.DomainEventEnvelope;
import io.crewscope.domain.shared.event.EventActor;
import io.crewscope.domain.shared.event.EventActorType;
import io.crewscope.domain.shared.event.EventType;
import io.crewscope.domain.shared.event.SchemaVersion;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.InvitationTokenDigest;
import io.crewscope.domain.team.MemberRole;
import io.crewscope.domain.team.MemberRoleId;
import io.crewscope.domain.team.RoleScope;
import io.crewscope.domain.team.Team;
import io.crewscope.domain.team.TeamInvitation;
import io.crewscope.domain.team.TeamInvitationStatus;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.team.TeamRole;
import io.crewscope.domain.team.event.TeamInvitationAccepted;
import io.crewscope.domain.team.event.TeamInvitationMembershipResult;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/** Creates one complete local identity chain and optionally consumes a Team invitation atomically. */
public final class LocalAccountRegistrationService {

    private static final String COMMAND_TYPE = "REGISTER_LOCAL_ACCOUNT";
    private static final String ACCOUNT_AGGREGATE = "USER_ACCOUNT";
    private static final String INVITATION_AGGREGATE = "TEAM_INVITATION";

    private final UserAccountRepository accounts;
    private final LoginIdentityRepository loginIdentities;
    private final LocalCredentialStore credentials;
    private final PrincipalRepository principals;
    private final AccountOrganizationBindingRepository bindings;
    private final TeamInvitationRepository invitations;
    private final TeamRepository teams;
    private final TeamMemberRepository members;
    private final TeamRoleRepository roles;
    private final MemberRoleRepository memberRoles;
    private final TeamInvitationAcceptanceService invitationAcceptance;
    private final Optional<InvitationTokenDigester> invitationDigester;
    private final LocalPasswordAuthentication passwords;
    private final DomainEventStore events;
    private final OutboxRepository outbox;
    private final CommandReceiptStore receipts;
    private final TransactionExecutor transactions;
    private final TimeProvider timeProvider;
    private final Executor persistenceExecutor;

    public LocalAccountRegistrationService(
            UserAccountRepository accounts,
            LoginIdentityRepository loginIdentities,
            LocalCredentialStore credentials,
            PrincipalRepository principals,
            AccountOrganizationBindingRepository bindings,
            TeamInvitationRepository invitations,
            TeamRepository teams,
            TeamMemberRepository members,
            TeamRoleRepository roles,
            MemberRoleRepository memberRoles,
            TeamInvitationAcceptanceService invitationAcceptance,
            Optional<InvitationTokenDigester> invitationDigester,
            LocalPasswordAuthentication passwords,
            DomainEventStore events,
            OutboxRepository outbox,
            CommandReceiptStore receipts,
            TransactionExecutor transactions,
            TimeProvider timeProvider,
            Executor persistenceExecutor) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.loginIdentities = Objects.requireNonNull(loginIdentities, "loginIdentities");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.principals = Objects.requireNonNull(principals, "principals");
        this.bindings = Objects.requireNonNull(bindings, "bindings");
        this.invitations = Objects.requireNonNull(invitations, "invitations");
        this.teams = Objects.requireNonNull(teams, "teams");
        this.members = Objects.requireNonNull(members, "members");
        this.roles = Objects.requireNonNull(roles, "roles");
        this.memberRoles = Objects.requireNonNull(memberRoles, "memberRoles");
        this.invitationAcceptance = Objects.requireNonNull(
                invitationAcceptance, "invitationAcceptance");
        this.invitationDigester = Objects.requireNonNull(
                invitationDigester, "invitationDigester");
        this.passwords = Objects.requireNonNull(passwords, "passwords");
        this.events = Objects.requireNonNull(events, "events");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.receipts = Objects.requireNonNull(receipts, "receipts");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
        this.persistenceExecutor = Objects.requireNonNull(persistenceExecutor, "persistenceExecutor");
    }

    /**
     * Performs memory-hard encoding outside PostgreSQL and establishes no Session before commit.
     * Completed replays must prove the submitted password against the committed Credential.
     */
    public CompletionStage<LocalAccountRegistrationResult> register(
            LocalAccountRegistrationContext context,
            LocalAccountRegistrationCommand command) {
        LocalAccountRegistrationContext trusted = Objects.requireNonNull(context, "context");
        LocalAccountRegistrationCommand requested = Objects.requireNonNull(command, "command");
        RegistrationAttempt attempt = validateAttempt(trusted, requested);
        Optional<CommandReceipt> completed;
        try {
            completed = receipts.findCompleted(
                    trusted.organizationId(),
                    trusted.idempotencyKey(),
                    COMMAND_TYPE,
                    attempt.requestHash());
        } catch (IdempotencyConflictException conflict) {
            throw registrationConflict();
        }
        if (completed.isPresent()) {
            return recoverReplay(trusted, requested, attempt, completed.orElseThrow());
        }
        requireNewRegistrationAllowed(trusted.registrationMode(), attempt.invitationDigest());
        return passwords.encodeForStorage(requested.revealPassword())
                .thenApplyAsync(
                        hash -> transactions.required(
                                () -> commit(trusted, attempt, hash)),
                        persistenceExecutor)
                .thenCompose(outcome -> outcome.replayed()
                        ? recoverReplay(trusted, requested, attempt, outcome.receipt())
                        : CompletableFuture.completedFuture(outcome.result().orElseThrow()));
    }

    private RegistrationAttempt validateAttempt(
            LocalAccountRegistrationContext context,
            LocalAccountRegistrationCommand command) {
        UtcTimestamp validationTime = timeProvider.now();
        UserAccount candidate = UserAccount.register(
                UserAccountId.generate(),
                command.username(),
                command.email(),
                command.displayName(),
                validationTime);
        Optional<InvitationTokenDigest> invitationDigest = command.invitationToken().map(token ->
                invitationDigester
                        .orElseThrow(() -> unavailable())
                        .digest(token));
        CommandRequestHash requestHash = CommandRequestHash.sha256(
                COMMAND_TYPE,
                context.organizationId().toString(),
                candidate.username().normalizedValue(),
                candidate.normalizedEmail().value(),
                candidate.displayName(),
                invitationDigest.map(InvitationTokenDigest::valueForPersistence).orElse(""));
        return new RegistrationAttempt(candidate, invitationDigest, requestHash);
    }

    private CommittedRegistration commit(
            LocalAccountRegistrationContext context,
            RegistrationAttempt attempt,
            LocalPasswordHash passwordHash) {
        UtcTimestamp now = timeProvider.now();
        UUID commandId = UUID.randomUUID();
        CommandReservation reservation;
        try {
            reservation = receipts.reserve(new CommandReservationRequest(
                    context.organizationId(),
                    context.idempotencyKey(),
                    COMMAND_TYPE,
                    attempt.requestHash(),
                    commandId,
                    context.correlationId(),
                    now));
        } catch (IdempotencyConflictException conflict) {
            throw registrationConflict();
        }
        if (!reservation.acquired()) {
            return CommittedRegistration.replayed(reservation.receipt().orElseThrow());
        }
        requireNewRegistrationAllowed(context.registrationMode(), attempt.invitationDigest());
        try {
            return persistNewRegistration(
                    context, attempt, passwordHash, commandId, now);
        } catch (AccountIdentifierConflictException
                | LoginIdentityConflictException
                | LocalCredentialConflictException
                | AccountOrganizationBindingConflictException conflict) {
            throw new LocalAccountRegistrationException(
                    LocalAccountRegistrationFailure.REGISTRATION_CONFLICT);
        }
    }

    private CommittedRegistration persistNewRegistration(
            LocalAccountRegistrationContext context,
            RegistrationAttempt attempt,
            LocalPasswordHash passwordHash,
            UUID commandId,
            UtcTimestamp now) {
        Optional<TeamInvitation> lockedInvitation = attempt.invitationDigest()
                .map(digest -> requireInvitation(digest, context.organizationId(), now));
        if (!context.registrationMode().allowsRegistration(lockedInvitation.isPresent())) {
            throw invitationRequired();
        }

        UserAccount draft = attempt.accountCandidate();
        UserAccount account = accounts.create(UserAccount.register(
                draft.id(),
                draft.username().displayValue(),
                draft.email(),
                draft.displayName(),
                now));
        LoginIdentity identity = loginIdentities.create(
                LoginIdentity.local(LoginIdentityId.generate(), account.id(), now));
        LocalCredentialMetadata metadata = LocalCredentialMetadata.create(
                LocalCredentialId.generate(), account.id(), passwordHash, now);
        credentials.create(metadata, passwordHash);
        Principal principal = principals.createLocalUser(Principal.create(
                PrincipalId.generate(),
                PrincipalScope.organization(context.organizationId()),
                PrincipalType.USER,
                Optional.empty(),
                account.displayName(),
                Optional.empty(),
                PrincipalVisibility.ORGANIZATION,
                now));
        AccountOrganizationBinding binding = bindings.create(AccountOrganizationBinding.bind(
                AccountOrganizationBindingId.generate(),
                account,
                context.organizationId(),
                principal,
                now));

        Optional<InvitationAcceptance> acceptance = lockedInvitation.map(invitation ->
                acceptInvitation(invitation, attempt.invitationDigest().orElseThrow(), account,
                        binding, principal, now));
        UUID accountEventId = appendAccountEvent(
                context, account, principal, acceptance.isPresent(), now);
        acceptance.ifPresent(value -> appendInvitationEvent(
                context, value, principal, now));
        CommandReceipt receipt = new CommandReceipt(
                commandId,
                accountEventId,
                account.version(),
                context.correlationId());
        receipts.complete(
                context.organizationId(),
                context.idempotencyKey(),
                receipt,
                now);
        LocalAccountRegistrationResult result = result(
                account,
                identity,
                binding,
                principal,
                acceptance,
                receipt,
                false);
        return CommittedRegistration.completed(result);
    }

    private InvitationAcceptance acceptInvitation(
            TeamInvitation invitation,
            InvitationTokenDigest digest,
            UserAccount account,
            AccountOrganizationBinding binding,
            Principal principal,
            UtcTimestamp now) {
        Team team = teams.lockById(invitation.scope().organizationId(), invitation.scope().teamId())
                .filter(Team::isActive)
                .orElseThrow(() -> invalidInvitation());
        TeamInvitationAcceptancePlan plan;
        try {
            plan = invitationAcceptance.planAcceptance(
                    invitation,
                    digest,
                    account,
                    binding,
                    team,
                    principal,
                    Optional.empty(),
                    TeamMemberId.generate(),
                    now);
        } catch (DomainException invalid) {
            throw invalidInvitation();
        }
        TeamRole role = roles.findByTeam(team.scope().organizationId(), team.id()).stream()
                .filter(candidate -> candidate.isBuiltIn(plan.targetRole()))
                .filter(TeamRole::isGrantable)
                .findFirst()
                .orElseThrow(() -> invalidInvitation());
        TeamMember member = members.create(plan.membership());
        memberRoles.create(MemberRole.grant(
                MemberRoleId.generate(),
                member,
                role,
                RoleScope.team(),
                invitation.invitedByPrincipalId(),
                now,
                now,
                Optional.empty()));
        TeamInvitation accepted = invitations.update(plan.invitation(), invitation.version());
        return new InvitationAcceptance(
                accepted,
                member,
                Optional.of(plan.membershipDisposition().eventResult()),
                team);
    }

    private TeamInvitation requireInvitation(
            InvitationTokenDigest digest,
            OrganizationId organizationId,
            UtcTimestamp now) {
        TeamInvitation invitation = invitations.lockByTokenDigest(digest)
                .orElseThrow(() -> invalidInvitation());
        if (!invitation.scope().organizationId().equals(organizationId)
                || !invitation.isPendingAt(now)) {
            throw invalidInvitation();
        }
        return invitation;
    }

    private UUID appendAccountEvent(
            LocalAccountRegistrationContext context,
            UserAccount account,
            Principal actor,
            boolean invited,
            UtcTimestamp now) {
        UUID eventId = UUID.randomUUID();
        DomainEventEnvelope<UserAccountRegistered> event = new DomainEventEnvelope<>(
                eventId,
                EventType.from("USER_ACCOUNT_REGISTERED"),
                SchemaVersion.V1,
                context.organizationId(),
                Optional.empty(),
                Optional.empty(),
                AggregateReference.of(ACCOUNT_AGGREGATE, account.id()),
                account.version(),
                EventActor.principal(EventActorType.USER, actor.id()),
                context.correlationId(),
                context.causationId(),
                Optional.of(context.idempotencyKey().value()),
                now,
                new UserAccountRegistered(
                        invited ? AccountRegistrationSource.INVITATION : AccountRegistrationSource.OPEN,
                        PlatformRole.USER));
        append(event);
        return eventId;
    }

    private void appendInvitationEvent(
            LocalAccountRegistrationContext context,
            InvitationAcceptance acceptance,
            Principal actor,
            UtcTimestamp now) {
        TeamInvitation invitation = acceptance.invitation();
        TeamMember membership = acceptance.membership();
        DomainEventEnvelope<TeamInvitationAccepted> event = new DomainEventEnvelope<>(
                UUID.randomUUID(),
                EventType.from("TEAM_INVITATION_ACCEPTED"),
                SchemaVersion.V1,
                context.organizationId(),
                Optional.of(invitation.scope().teamId()),
                Optional.of(acceptance.team().defaultWorkspaceId()),
                AggregateReference.of(INVITATION_AGGREGATE, invitation.id()),
                invitation.version(),
                EventActor.principal(EventActorType.USER, actor.id()),
                context.correlationId(),
                context.causationId(),
                Optional.of(context.idempotencyKey().value()),
                now,
                new TeamInvitationAccepted(
                        invitation.acceptedByAccountId().orElseThrow().value(),
                        membership.id().value(),
                        invitation.targetRole(),
                        acceptance.membershipResult().orElseThrow()));
        append(event);
    }

    private void append(DomainEventEnvelope<?> event) {
        events.append(event);
        outbox.enqueue(PendingOutboxEvent.fromDomain(UUID.randomUUID(), event));
    }

    private CompletionStage<LocalAccountRegistrationResult> recoverReplay(
            LocalAccountRegistrationContext context,
            LocalAccountRegistrationCommand command,
            RegistrationAttempt attempt,
            CommandReceipt receipt) {
        ReplayIdentity identity = loadReplayIdentity(context, attempt);
        return passwords.verify(
                        command.revealPassword(),
                        credentials.findByAccountIdForAuthentication(identity.account().id()),
                        identity.account().canAuthenticate())
                .thenApplyAsync(verification -> {
                    if (!verification.authenticated()) {
                        throw new LocalAccountRegistrationException(
                                LocalAccountRegistrationFailure.REPLAY_AUTHENTICATION_FAILED);
                    }
                    Optional<InvitationAcceptance> acceptance = loadReplayInvitation(
                            context, attempt, identity);
                    return result(
                            identity.account(),
                            identity.loginIdentity(),
                            identity.binding(),
                            identity.principal(),
                            acceptance,
                            receipt,
                            true);
                }, persistenceExecutor);
    }

    private ReplayIdentity loadReplayIdentity(
            LocalAccountRegistrationContext context,
            RegistrationAttempt attempt) {
        UserAccount expected = attempt.accountCandidate();
        UserAccount account = accounts.findByUsername(expected.username())
                .filter(UserAccount::canAuthenticate)
                .filter(candidate -> candidate.platformRole() == PlatformRole.USER)
                .filter(candidate -> candidate.normalizedEmail().equals(expected.normalizedEmail()))
                .filter(candidate -> candidate.displayName().equals(expected.displayName()))
                .orElseThrow(() -> unavailable());
        LoginIdentity identity = loginIdentities.findByAccountId(account.id()).stream()
                .filter(LoginIdentity::isUsable)
                .filter(candidate -> candidate.provider().isLocal())
                .findFirst()
                .orElseThrow(() -> unavailable());
        AccountOrganizationBinding binding = bindings
                .findByAccountOrganizationKey(
                        new AccountOrganizationKey(account.id(), context.organizationId()))
                .filter(AccountOrganizationBinding::isUsable)
                .orElseThrow(() -> unavailable());
        Principal principal = principals.findById(context.organizationId(), binding.principalId())
                .filter(binding::isCompatibleWith)
                .orElseThrow(() -> unavailable());
        return new ReplayIdentity(account, identity, binding, principal);
    }

    private Optional<InvitationAcceptance> loadReplayInvitation(
            LocalAccountRegistrationContext context,
            RegistrationAttempt attempt,
            ReplayIdentity identity) {
        return attempt.invitationDigest().map(digest -> {
            TeamInvitation invitation = invitations.findByTokenDigest(digest)
                    .filter(candidate -> candidate.status() == TeamInvitationStatus.ACCEPTED)
                    .filter(candidate -> candidate.scope().organizationId()
                            .equals(context.organizationId()))
                    .filter(candidate -> candidate.acceptedByAccountId()
                            .filter(identity.account().id()::equals)
                            .isPresent())
                    .orElseThrow(() -> unavailable());
            TeamMember member = members.findById(
                            context.organizationId(),
                            invitation.acceptedMemberId().orElseThrow())
                    .filter(TeamMember::canParticipate)
                    .filter(candidate -> candidate.userPrincipalId().equals(identity.principal().id()))
                    .orElseThrow(() -> unavailable());
            Team team = teams.findById(context.organizationId(), invitation.scope().teamId())
                    .filter(Team::isActive)
                    .orElseThrow(() -> unavailable());
            // Replays reconstruct committed coordinates only; no historical persistence action is
            // invented because a replay never appends another acceptance event.
            return new InvitationAcceptance(invitation, member, Optional.empty(), team);
        });
    }

    private static LocalAccountRegistrationResult result(
            UserAccount account,
            LoginIdentity identity,
            AccountOrganizationBinding binding,
            Principal principal,
            Optional<InvitationAcceptance> acceptance,
            CommandReceipt receipt,
            boolean replayed) {
        return new LocalAccountRegistrationResult(
                account,
                identity,
                binding,
                principal,
                acceptance.map(InvitationAcceptance::invitation),
                acceptance.map(InvitationAcceptance::membership),
                receipt,
                replayed);
    }

    private static void requireNewRegistrationAllowed(
            RegistrationMode mode,
            Optional<InvitationTokenDigest> invitationDigest) {
        RegistrationMode requiredMode = Objects.requireNonNull(mode, "mode");
        if (requiredMode == RegistrationMode.DISABLED) {
            throw new LocalAccountRegistrationException(
                    LocalAccountRegistrationFailure.REGISTRATION_DISABLED);
        }
        if (requiredMode.requiresInvitation() && invitationDigest.isEmpty()) {
            throw invitationRequired();
        }
    }

    private static LocalAccountRegistrationException invitationRequired() {
        return new LocalAccountRegistrationException(
                LocalAccountRegistrationFailure.INVITATION_REQUIRED);
    }

    private static LocalAccountRegistrationException invalidInvitation() {
        return new LocalAccountRegistrationException(
                LocalAccountRegistrationFailure.INVITATION_INVALID);
    }

    private static LocalAccountRegistrationException registrationConflict() {
        return new LocalAccountRegistrationException(
                LocalAccountRegistrationFailure.REGISTRATION_CONFLICT);
    }

    private static LocalAccountRegistrationException unavailable() {
        return new LocalAccountRegistrationException(
                LocalAccountRegistrationFailure.REGISTRATION_UNAVAILABLE);
    }

    private record RegistrationAttempt(
            UserAccount accountCandidate,
            Optional<InvitationTokenDigest> invitationDigest,
            CommandRequestHash requestHash) {

        private RegistrationAttempt {
            accountCandidate = Objects.requireNonNull(accountCandidate, "accountCandidate");
            invitationDigest = Objects.requireNonNull(invitationDigest, "invitationDigest");
            requestHash = Objects.requireNonNull(requestHash, "requestHash");
        }
    }

    private record InvitationAcceptance(
            TeamInvitation invitation,
            TeamMember membership,
            Optional<TeamInvitationMembershipResult> membershipResult,
            Team team) {}

    private record ReplayIdentity(
            UserAccount account,
            LoginIdentity loginIdentity,
            AccountOrganizationBinding binding,
            Principal principal) {}

    private record CommittedRegistration(
            Optional<LocalAccountRegistrationResult> result,
            CommandReceipt receipt,
            boolean replayed) {

        private static CommittedRegistration completed(LocalAccountRegistrationResult result) {
            return new CommittedRegistration(Optional.of(result), result.receipt(), false);
        }

        private static CommittedRegistration replayed(CommandReceipt receipt) {
            return new CommittedRegistration(Optional.empty(), receipt, true);
        }
    }
}
