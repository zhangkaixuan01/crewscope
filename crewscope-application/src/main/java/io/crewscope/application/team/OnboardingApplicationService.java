package io.crewscope.application.team;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.identity.UserAccountRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.UserAccount;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.team.TeamInitialization;
import java.util.Objects;

/** Serializes current-account onboarding and delegates the complete foundation to M1. */
public final class OnboardingApplicationService {

    private final UserAccountRepository accounts;
    private final TeamRepository teams;
    private final TeamApplicationService teamService;
    private final TransactionExecutor transactions;

    public OnboardingApplicationService(
            UserAccountRepository accounts,
            TeamRepository teams,
            TeamApplicationService teamService,
            TransactionExecutor transactions) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.teams = Objects.requireNonNull(teams, "teams");
        this.teamService = Objects.requireNonNull(teamService, "teamService");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    /** Derives onboarding exclusively from the current Principal's active Team Memberships. */
    public OnboardingStatus status(OnboardingAccountContext context) {
        OnboardingAccountContext trusted = requireContext(context);
        return transactions.required(() -> {
            requireCurrentAccount(trusted, false);
            return statusFor(trusted.teamAccess().actor());
        });
    }

    /** Locks the Account before the replay-aware zero-Team guard and M1 creation transaction. */
    public CommandExecution<TeamInitialization> createFirstTeam(
            OnboardingAccountContext context,
            TeamCommandContext teamContext,
            CreateTeamCommand command) {
        OnboardingAccountContext trusted = requireContext(context);
        TeamCommandContext trustedTeamContext = Objects.requireNonNull(teamContext, "teamContext");
        CreateTeamCommand requested = Objects.requireNonNull(command, "command");
        if (!trusted.teamAccess().equals(trustedTeamContext.access())) {
            throw denied();
        }
        return transactions.required(() -> {
            requireCurrentAccount(trusted, true);
            return teamService.createFirstTeam(trustedTeamContext, requested);
        });
    }

    private OnboardingStatus statusFor(Principal actor) {
        int count = teams.findActiveByMember(
                        actor.scope().organizationId(), actor.id())
                .size();
        return OnboardingStatus.fromActiveTeamCount(count);
    }

    private UserAccount requireCurrentAccount(
            OnboardingAccountContext context, boolean lock) {
        UserAccount account = (lock
                        ? accounts.findByIdForUpdate(context.accountId())
                        : accounts.findById(context.accountId()))
                .filter(UserAccount::canAuthenticate)
                .filter(candidate -> candidate.securityVersion()
                        .equals(context.sessionSecurityVersion()))
                .orElseThrow(OnboardingApplicationService::denied);
        return account;
    }

    private static OnboardingAccountContext requireContext(OnboardingAccountContext context) {
        OnboardingAccountContext trusted = Objects.requireNonNull(context, "context");
        Principal actor = trusted.teamAccess().actor();
        if (actor.type() != PrincipalType.USER
                || !actor.canAct()
                || actor.scope().teamId().isPresent()) {
            throw denied();
        }
        return trusted;
    }

    private static PolicyDeniedException denied() {
        return new PolicyDeniedException("perform current-account onboarding");
    }
}
