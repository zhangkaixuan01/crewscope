package io.crewscope.application.identity;

import io.crewscope.application.command.CommandReceipt;
import io.crewscope.domain.identity.AccountOrganizationBinding;
import io.crewscope.domain.identity.LoginIdentity;
import io.crewscope.domain.identity.PlatformRole;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.UserAccount;
import io.crewscope.domain.team.TeamInvitation;
import io.crewscope.domain.team.TeamMember;
import java.util.Objects;
import java.util.Optional;

/** Committed registration coordinates required by Session establishment and the public response. */
public record LocalAccountRegistrationResult(
        UserAccount account,
        LoginIdentity loginIdentity,
        AccountOrganizationBinding binding,
        Principal principal,
        Optional<TeamInvitation> acceptedInvitation,
        Optional<TeamMember> membership,
        CommandReceipt receipt,
        boolean replayed) {

    public LocalAccountRegistrationResult {
        account = Objects.requireNonNull(account, "account");
        loginIdentity = Objects.requireNonNull(loginIdentity, "loginIdentity");
        binding = Objects.requireNonNull(binding, "binding");
        principal = Objects.requireNonNull(principal, "principal");
        acceptedInvitation = Objects.requireNonNull(acceptedInvitation, "acceptedInvitation");
        membership = Objects.requireNonNull(membership, "membership");
        receipt = Objects.requireNonNull(receipt, "receipt");
        if (account.platformRole() != PlatformRole.USER
                || !account.id().equals(loginIdentity.accountId())
                || !account.id().equals(binding.accountId())
                || !binding.principalId().equals(principal.id())
                || !binding.isUsable()
                || !binding.isCompatibleWith(principal)
                || acceptedInvitation.isPresent() != membership.isPresent()) {
            throw new IllegalArgumentException("registration result identity chain is inconsistent");
        }
        if (acceptedInvitation.isPresent()) {
            TeamInvitation invitation = acceptedInvitation.orElseThrow();
            TeamMember member = membership.orElseThrow();
            if (!invitation.acceptedByAccountId().filter(account.id()::equals).isPresent()
                    || !invitation.acceptedMemberId().filter(member.id()::equals).isPresent()
                    || !invitation.scope().equals(member.scope())) {
                throw new IllegalArgumentException("registration invitation result is inconsistent");
            }
        }
    }
}
