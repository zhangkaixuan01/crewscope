package io.crewscope.application.team;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.application.identity.UserAccountRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.identity.SecurityVersion;
import io.crewscope.domain.identity.UserAccount;
import io.crewscope.domain.identity.UserAccountId;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.Team;
import io.crewscope.domain.team.TeamInitialization;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Current Account validation and Account-lock delegation contract for M7-A04. */
class OnboardingApplicationServiceM7A04Test {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-29T02:00:00Z");

    private UserAccountRepository accounts;
    private TeamRepository teams;
    private TeamApplicationService teamService;
    private UserAccount account;
    private Principal principal;
    private OnboardingAccountContext context;
    private OnboardingApplicationService service;

    @BeforeEach
    void setUp() {
        accounts = mock(UserAccountRepository.class);
        teams = mock(TeamRepository.class);
        teamService = mock(TeamApplicationService.class);
        account = UserAccount.register(
                UserAccountId.generate(), "alice", "alice@example.com", "Alice", NOW);
        OrganizationId organizationId = OrganizationId.generate();
        principal = Principal.create(
                PrincipalId.generate(),
                PrincipalScope.organization(organizationId),
                PrincipalType.USER,
                Optional.empty(),
                "Alice",
                Optional.empty(),
                PrincipalVisibility.ORGANIZATION,
                NOW);
        TeamAccessContext access = new TeamAccessContext(principal, false);
        context = new OnboardingAccountContext(
                account.id(), account.securityVersion(), access);
        when(accounts.findById(account.id())).thenReturn(Optional.of(account));
        when(accounts.findByIdForUpdate(account.id())).thenReturn(Optional.of(account));
        service = new OnboardingApplicationService(
                accounts, teams, teamService, new DirectTransactions());
    }

    @Test
    void noActiveTeamRequiresOnboarding() {
        when(teams.findActiveByMember(
                        principal.scope().organizationId(), principal.id()))
                .thenReturn(List.of());

        OnboardingStatus status = service.status(context);

        assertEquals(OnboardingState.TEAM_REQUIRED, status.state());
        assertTrue(status.onboardingRequired());
        assertEquals(0, status.activeTeamCount());
    }

    @Test
    void anyActiveTeamSkipsOnboarding() {
        when(teams.findActiveByMember(
                        principal.scope().organizationId(), principal.id()))
                .thenReturn(List.of(mock(Team.class), mock(Team.class)));

        OnboardingStatus status = service.status(context);

        assertEquals(OnboardingState.COMPLETE, status.state());
        assertFalse(status.onboardingRequired());
        assertEquals(2, status.activeTeamCount());
    }

    @Test
    void creationLocksTheAccountBeforeDelegatingToTheReplayAwareM1Command() {
        TeamCommandContext teamContext = teamContext(context.teamAccess(), "onboarding-create");
        @SuppressWarnings("unchecked")
        CommandExecution<TeamInitialization> execution = mock(CommandExecution.class);
        when(teamService.createFirstTeam(any(), any())).thenReturn(execution);

        CommandExecution<TeamInitialization> result = service.createFirstTeam(
                context, teamContext, new CreateTeamCommand("Platform Team"));

        assertEquals(execution, result);
        verify(accounts).findByIdForUpdate(account.id());
        verify(teamService).createFirstTeam(
                teamContext, new CreateTeamCommand("Platform Team"));
    }

    @Test
    void staleSessionSecurityVersionFailsBeforeTeamCreation() {
        OnboardingAccountContext stale = new OnboardingAccountContext(
                account.id(), new SecurityVersion(9), context.teamAccess());

        assertThrows(
                PolicyDeniedException.class,
                () -> service.createFirstTeam(
                        stale,
                        teamContext(stale.teamAccess(), "stale-session"),
                        new CreateTeamCommand("Denied Team")));

        verify(teamService, never()).createFirstTeam(any(), any());
    }

    @Test
    void commandCannotSubstituteAnotherPrincipalAfterResolution() {
        Principal other = Principal.create(
                PrincipalId.generate(),
                principal.scope(),
                PrincipalType.USER,
                Optional.empty(),
                "Other",
                Optional.empty(),
                PrincipalVisibility.ORGANIZATION,
                NOW);
        TeamCommandContext substituted = teamContext(
                new TeamAccessContext(other, false), "substituted-principal");

        assertThrows(
                PolicyDeniedException.class,
                () -> service.createFirstTeam(
                        context, substituted, new CreateTeamCommand("Denied Team")));

        verify(accounts, never()).findByIdForUpdate(any());
        verify(teamService, never()).createFirstTeam(any(), any());
    }

    private static TeamCommandContext teamContext(TeamAccessContext access, String key) {
        return new TeamCommandContext(
                access,
                IdempotencyKey.from(key),
                UUID.randomUUID(),
                Optional.empty());
    }

    private static final class DirectTransactions implements TransactionExecutor {

        @Override
        public <T> T required(Supplier<T> operation) {
            return operation.get();
        }
    }
}
