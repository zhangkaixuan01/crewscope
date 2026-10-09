package io.crewscope.server.collaboration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.crewscope.server.collaboration.CollaborationConnectionRegistry.Admission;
import io.crewscope.server.collaboration.CollaborationConnectionRegistry.Reason;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Admission limits, reverse index and concurrency of the in-process connection registry. */
class CollaborationConnectionRegistryTest {

  @Test
  void admitsWithinPerPrincipalBudgetAndRejectsTheNext() {
    CollaborationConnectionRegistry registry = new CollaborationConnectionRegistry(2, 10, 20);
    UUID account = UUID.randomUUID();

    assertThat(registry.admit("c1", account)).isEqualTo(new Admission.Accepted(false));
    assertThat(registry.admit("c2", account)).isEqualTo(new Admission.Accepted(false));
    assertThat(registry.admit("c3", account))
        .isEqualTo(new Admission.Rejected(Reason.PRINCIPAL_LIMIT));

    assertThat(registry.connectionsFor(account)).isEqualTo(2);
    assertThat(registry.totalConnections()).isEqualTo(2);
  }

  @Test
  void releasingAConnectionReturnsItsSlotToTheSameAccount() {
    CollaborationConnectionRegistry registry = new CollaborationConnectionRegistry(1, 10, 20);
    UUID account = UUID.randomUUID();

    assertThat(registry.admit("c1", account)).isEqualTo(new Admission.Accepted(false));
    assertThat(registry.release("c1")).isTrue();
    assertThat(registry.release("c1")).isFalse();
    assertThat(registry.totalConnections()).isZero();

    assertThat(registry.admit("c2", account)).isEqualTo(new Admission.Accepted(false));
  }

  @Test
  void nodeHardLimitRejectsRegardlessOfAccountAndSoftBudgetOnlyFlags() {
    CollaborationConnectionRegistry registry = new CollaborationConnectionRegistry(10, 1, 2);

    assertThat(registry.admit("c1", UUID.randomUUID()))
        .isEqualTo(new Admission.Accepted(false));
    // Second connection is within the hard limit but over the soft budget: flagged, admitted.
    assertThat(registry.admit("c2", UUID.randomUUID()))
        .isEqualTo(new Admission.Accepted(true));
    assertThat(registry.admit("c3", UUID.randomUUID()))
        .isEqualTo(new Admission.Rejected(Reason.NODE_LIMIT));
  }

  @Test
  void duplicateConnectionIdIsRejectedWithoutSideEffects() {
    CollaborationConnectionRegistry registry = new CollaborationConnectionRegistry(5, 10, 20);
    UUID account = UUID.randomUUID();

    assertThat(registry.admit("c1", account)).isEqualTo(new Admission.Accepted(false));
    assertThat(registry.admit("c1", UUID.randomUUID()))
        .isEqualTo(new Admission.Rejected(Reason.DUPLICATE_CONNECTION_ID));
    assertThat(registry.totalConnections()).isEqualTo(1);
    assertThat(registry.connectionsFor(account)).isEqualTo(1);
  }

  @Test
  void snapshotReflectsRegisteredConnectionsAndIgnoresLaterMutation() {
    CollaborationConnectionRegistry registry = new CollaborationConnectionRegistry(5, 10, 20);
    UUID account = UUID.randomUUID();
    registry.admit("c1", account);

    var snapshot = registry.snapshot();
    assertThat(snapshot).hasSize(1);
    var connection = snapshot.iterator().next();
    assertThat(connection.connectionId()).isEqualTo("c1");
    assertThat(connection.accountId()).isEqualTo(account);
    assertThatThrownBy(() -> snapshot.add(null)).isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void concurrentAdmissionToOneAccountNeverExceedsThePerPrincipalCap() throws Exception {
    CollaborationConnectionRegistry registry = new CollaborationConnectionRegistry(4, 100, 200);
    UUID sharedAccount = UUID.randomUUID();
    int contenders = 16;

    List<Callable<Admission>> attempts = new ArrayList<>();
    for (int i = 0; i < contenders; i++) {
      String id = "race-" + i;
      attempts.add(() -> registry.admit(id, sharedAccount));
    }
    List<Admission> results = runConcurrently(attempts);

    long admitted =
        results.stream().filter(result -> result instanceof Admission.Accepted).count();
    long rejected =
        results.stream()
            .filter(result -> result.equals(new Admission.Rejected(Reason.PRINCIPAL_LIMIT)))
            .count();
    assertThat(admitted).isEqualTo(4);
    assertThat(rejected).isEqualTo(contenders - 4);
    assertThat(registry.connectionsFor(sharedAccount)).isEqualTo(4);
  }

  @Test
  void concurrentAdmissionAcrossAccountsRespectsTheNodeHardLimit() throws Exception {
    CollaborationConnectionRegistry registry = new CollaborationConnectionRegistry(100, 4, 8);
    int contenders = 16;

    List<Callable<Admission>> attempts = new ArrayList<>();
    for (int i = 0; i < contenders; i++) {
      String id = "node-" + i;
      UUID account = UUID.randomUUID();
      attempts.add(() -> registry.admit(id, account));
    }
    List<Admission> results = runConcurrently(attempts);

    long admitted =
        results.stream().filter(result -> result instanceof Admission.Accepted).count();
    long rejected =
        results.stream()
            .filter(result -> result.equals(new Admission.Rejected(Reason.NODE_LIMIT)))
            .count();
    assertThat(admitted).isEqualTo(8);
    assertThat(rejected).isEqualTo(contenders - 8);
    assertThat(registry.totalConnections()).isEqualTo(8);
  }

  @Test
  void constructorRejectsInconsistentLimits() {
    assertThatThrownBy(() -> new CollaborationConnectionRegistry(0, 10, 20))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CollaborationConnectionRegistry(5, 0, 20))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CollaborationConnectionRegistry(5, 20, 20))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static List<Admission> runConcurrently(List<Callable<Admission>> attempts)
      throws Exception {
    CountDownLatch startGate = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(attempts.size());
    try {
      List<Future<Admission>> futures =
          attempts.stream()
              .map(
                  attempt ->
                      executor.submit(
                          () -> {
                            startGate.await();
                            return attempt.call();
                          }))
              .toList();
      startGate.countDown();
      List<Admission> results = new ArrayList<>();
      for (Future<Admission> future : futures) {
        results.add(future.get(10, TimeUnit.SECONDS));
      }
      return results;
    } finally {
      executor.shutdownNow();
    }
  }
}
