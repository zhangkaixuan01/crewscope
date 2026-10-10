package io.crewscope.server.collaboration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.crewscope.domain.collaboration.CollaborationResourceScope;
import io.crewscope.domain.collaboration.TeamScope;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import reactor.core.publisher.Mono;

/** Cache freshness and fail-closed probe behavior of the A01 revocation revalidator. */
class CollaborationRevocationRevalidatorTest {

  private static final Instant T0 = Instant.parse("2026-10-10T08:00:00Z");

  private final OrganizationId organizationId = OrganizationId.generate();
  private final TeamId teamId = TeamId.generate();
  private final Authentication authentication = mock(Authentication.class);

  @Test
  void aFreshVerdictIsServedFromTheCacheWithoutAnotherProbe() {
    CountingAuthorizer authorizer = new CountingAuthorizer();
    MutableClock clock = new MutableClock(T0);
    CollaborationRevocationRevalidator revalidator =
        new CollaborationRevocationRevalidator(authorizer, Duration.ofSeconds(5), clock);

    assertTrue(revalidator.cached("account:a", organizationId, teamId).isEmpty(),
        "an unknown principal has no cached verdict");

    assertTrue(revalidator.revalidate("account:a", authentication, organizationId, teamId)
        .block(Duration.ofSeconds(5)));
    assertEquals(Optional.of(Boolean.TRUE),
        revalidator.cached("account:a", organizationId, teamId));
    assertEquals(1, authorizer.probes.get());

    // Inside the cache interval the verdict serves from memory, no probe runs.
    clock.now = T0.plusSeconds(4);
    assertEquals(Optional.of(Boolean.TRUE),
        revalidator.cached("account:a", organizationId, teamId));
    assertEquals(1, authorizer.probes.get());

    // Once the interval elapses the entry is stale and asks for a probe again.
    clock.now = T0.plusSeconds(5);
    assertTrue(revalidator.cached("account:a", organizationId, teamId).isEmpty());
  }

  @Test
  void aDeniedProbeFailsClosedAndIsCached() {
    CountingAuthorizer authorizer = new CountingAuthorizer();
    authorizer.decision = CollaborationSubscriptionAuthorizer.Decision.DENIED;
    CollaborationRevocationRevalidator revalidator = new CollaborationRevocationRevalidator(
        authorizer, Duration.ofSeconds(5), new MutableClock(T0));

    assertEquals(Boolean.FALSE, revalidator
        .revalidate("account:a", authentication, organizationId, teamId)
        .block(Duration.ofSeconds(5)));
    assertEquals(Optional.of(Boolean.FALSE),
        revalidator.cached("account:a", organizationId, teamId));
  }

  @Test
  void anAuthorizerFailureResolvesDeniedWithoutErroring() {
    CountingAuthorizer authorizer = new CountingAuthorizer();
    authorizer.failure = new IllegalStateException("pg blip");
    CollaborationRevocationRevalidator revalidator = new CollaborationRevocationRevalidator(
        authorizer, Duration.ofSeconds(5), new MutableClock(T0));

    assertEquals(Boolean.FALSE, revalidator
        .revalidate("account:a", authentication, organizationId, teamId)
        .block(Duration.ofSeconds(5)),
        "an undeterminable verdict must fail closed");
  }

  @Test
  void concurrentRevalidationsShareOneInFlightProbe() {
    CountingAuthorizer authorizer = new CountingAuthorizer();
    CollaborationRevocationRevalidator revalidator = new CollaborationRevocationRevalidator(
        authorizer, Duration.ofSeconds(5), new MutableClock(T0));

    Mono<Boolean> probe = revalidator.revalidate("account:a", authentication, organizationId, teamId);
    probe.block(Duration.ofSeconds(5));
    probe.block(Duration.ofSeconds(5));

    assertEquals(1, authorizer.probes.get(),
        "the same completed probe answers repeated calls without re-probing");
  }

  private static final class CountingAuthorizer implements CollaborationSubscriptionAuthorizer {
    private final AtomicInteger probes = new AtomicInteger();
    private CollaborationSubscriptionAuthorizer.Decision decision =
        CollaborationSubscriptionAuthorizer.Decision.ALLOWED;
    private RuntimeException failure;

    @Override
    public Mono<CollaborationSubscriptionAuthorizer.Decision> authorize(
        Authentication subject, CollaborationResourceScope scope) {
      probes.incrementAndGet();
      if (failure != null) {
        return Mono.error(failure);
      }
      return Mono.just(decision);
    }
  }

  private static final class MutableClock extends java.time.Clock {
    private Instant now;

    private MutableClock(Instant now) {
      this.now = now;
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public java.time.Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
