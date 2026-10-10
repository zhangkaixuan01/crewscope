package io.crewscope.server.config.collaboration;

import io.crewscope.application.identity.CurrentAccountSnapshotReader;
import io.crewscope.server.collaboration.CollaborationDisplayNameResolver;
import io.crewscope.server.security.AccountSessionSubject;
import io.crewscope.server.security.AuthenticationSubjectExtractor;
import io.crewscope.server.security.AuthenticatedSubject;
import io.crewscope.server.security.ExternalAuthenticatedSubject;
import java.util.Objects;
import org.springframework.security.core.Authentication;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * Account display names for collaboration signal frames (M11-A01). The frames show the
 * account's own display name — the ADR-024 boundary: no per-team member name is resolved
 * here, and a later revision may swap the query source without touching the frame shape.
 *
 * <p>This resolver is presentation-only and never errors: an account that vanished, a
 * repository failure or an unrecognized subject all fall back to an empty name, because a
 * degraded presence label must never fail the connection that carries it.</p>
 */
final class RepositoryCollaborationDisplayNameResolver
    implements CollaborationDisplayNameResolver {

  private final AuthenticationSubjectExtractor subjectExtractor;
  private final CurrentAccountSnapshotReader accounts;
  private final Scheduler blockingBridge;

  RepositoryCollaborationDisplayNameResolver(
      AuthenticationSubjectExtractor subjectExtractor, CurrentAccountSnapshotReader accounts) {
    this(subjectExtractor, accounts, Schedulers.boundedElastic());
  }

  /** Test seam: the scheduler is injectable so a fake stack never needs a real pool. */
  RepositoryCollaborationDisplayNameResolver(
      AuthenticationSubjectExtractor subjectExtractor,
      CurrentAccountSnapshotReader accounts,
      Scheduler blockingBridge) {
    this.subjectExtractor = Objects.requireNonNull(subjectExtractor, "subjectExtractor");
    this.accounts = Objects.requireNonNull(accounts, "accounts");
    this.blockingBridge = Objects.requireNonNull(blockingBridge, "blockingBridge");
  }

  @Override
  public Mono<String> resolve(Authentication authentication) {
    AuthenticatedSubject subject;
    try {
      subject = subjectExtractor.extract(authentication);
    } catch (RuntimeException unrecognized) {
      return Mono.just("");
    }
    if (subject == null) {
      return Mono.just("");
    }
    if (subject instanceof ExternalAuthenticatedSubject external) {
      // The external subject carries its provider-supplied name already.
      return Mono.just(external.displayName());
    }
    if (subject instanceof AccountSessionSubject account) {
      return Mono.fromCallable(
              () ->
                  accounts
                      .findByAccountId(account.accountId())
                      .map(snapshot -> snapshot.account().displayName())
                      .orElse(""))
          .subscribeOn(blockingBridge)
          .onErrorResume(failure -> Mono.just(""));
    }
    return Mono.just("");
  }
}
