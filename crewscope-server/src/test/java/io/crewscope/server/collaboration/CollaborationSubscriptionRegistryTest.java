package io.crewscope.server.collaboration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** In-memory subscription topology: idempotent add, per-connection cap, reverse index. */
class CollaborationSubscriptionRegistryTest {

  private static final String CONNECTION = "conn-1";

  @Test
  void addReturnsFreshHandleAndIndexesTheScope() {
    CollaborationSubscriptionRegistry registry = new CollaborationSubscriptionRegistry(3);

    CollaborationSubscriptionRegistry.Addition added = registry.add(CONNECTION, "scope:a");

    assertThat(added).isInstanceOf(CollaborationSubscriptionRegistry.Added.class);
    assertThat(((CollaborationSubscriptionRegistry.Added) added).subscriptionId()).isNotBlank();
    assertThat(registry.connectionsFor("scope:a")).containsExactly(CONNECTION);
    assertThat(registry.totalSubscriptions()).isEqualTo(1);
  }

  @Test
  void reSubscribingTheSameScopeIsIdempotentAndKeepsTheOriginalHandle() {
    CollaborationSubscriptionRegistry registry = new CollaborationSubscriptionRegistry(3);
    String firstHandle =
        ((CollaborationSubscriptionRegistry.Added) registry.add(CONNECTION, "scope:a"))
            .subscriptionId();

    CollaborationSubscriptionRegistry.Addition second = registry.add(CONNECTION, "scope:a");

    assertThat(second)
        .isEqualTo(new CollaborationSubscriptionRegistry.AlreadySubscribed(firstHandle));
    assertThat(registry.connectionsFor("scope:a")).containsExactly(CONNECTION);
    assertThat(registry.totalSubscriptions()).isEqualTo(1);
    assertThat(registry.subscriptionId(CONNECTION, "scope:a")).isEqualTo(Optional.of(firstHandle));
  }

  @Test
  void atLimitRejectsNewScopesButNotIdempotentReSubscribes() {
    CollaborationSubscriptionRegistry registry = new CollaborationSubscriptionRegistry(2);
    registry.add(CONNECTION, "scope:a");
    registry.add(CONNECTION, "scope:b");

    assertThat(registry.add(CONNECTION, "scope:c"))
        .isEqualTo(new CollaborationSubscriptionRegistry.AtLimit(2));
    // An existing scope still acks with its handle instead of tripping the limit.
    assertThat(registry.add(CONNECTION, "scope:a"))
        .isInstanceOf(CollaborationSubscriptionRegistry.AlreadySubscribed.class);
  }

  @Test
  void theLimitIsPerConnectionNotGlobal() {
    CollaborationSubscriptionRegistry registry = new CollaborationSubscriptionRegistry(1);

    assertThat(registry.add("conn-1", "scope:a"))
        .isInstanceOf(CollaborationSubscriptionRegistry.Added.class);
    assertThat(registry.add("conn-2", "scope:a"))
        .isInstanceOf(CollaborationSubscriptionRegistry.Added.class);
    assertThat(registry.connectionsFor("scope:a")).containsExactlyInAnyOrder("conn-1", "conn-2");
  }

  @Test
  void removeByHandleReturnsTheScopeAndDropsTheReverseIndexEntry() {
    CollaborationSubscriptionRegistry registry = new CollaborationSubscriptionRegistry(3);
    String handle =
        ((CollaborationSubscriptionRegistry.Added) registry.add(CONNECTION, "scope:a"))
            .subscriptionId();

    assertThat(registry.remove(CONNECTION, handle)).isEqualTo(Optional.of("scope:a"));
    assertThat(registry.connectionsFor("scope:a")).isEmpty();
    assertThat(registry.totalSubscriptions()).isZero();
    // The freed slot is usable again.
    assertThat(registry.add(CONNECTION, "scope:a"))
        .isInstanceOf(CollaborationSubscriptionRegistry.Added.class);
  }

  @Test
  void removeWithUnknownHandleChangesNothing() {
    CollaborationSubscriptionRegistry registry = new CollaborationSubscriptionRegistry(3);
    registry.add(CONNECTION, "scope:a");

    assertThat(registry.remove(CONNECTION, UUID.randomUUID().toString())).isEmpty();
    assertThat(registry.remove("conn-unknown", "any")).isEmpty();
    assertThat(registry.totalSubscriptions()).isEqualTo(1);
  }

  @Test
  void removeAllDropsEveryScopeOfTheConnectionOnly() {
    CollaborationSubscriptionRegistry registry = new CollaborationSubscriptionRegistry(3);
    registry.add(CONNECTION, "scope:a");
    registry.add(CONNECTION, "scope:b");
    registry.add("conn-2", "scope:a");

    assertThat(registry.removeAll(CONNECTION)).containsExactlyInAnyOrder("scope:a", "scope:b");
    assertThat(registry.connectionsFor("scope:a")).containsExactly("conn-2");
    assertThat(registry.connectionsFor("scope:b")).isEmpty();
    assertThat(registry.removeAll(CONNECTION)).isEmpty();
  }

  @Test
  void scopeKeysOfListsTheConnectionsActiveScopesOnly() {
    CollaborationSubscriptionRegistry registry = new CollaborationSubscriptionRegistry(3);
    registry.add(CONNECTION, "scope:a");
    registry.add(CONNECTION, "scope:b");

    assertThat(registry.scopeKeysOf(CONNECTION)).containsExactlyInAnyOrder("scope:a", "scope:b");
    assertThat(registry.scopeKeysOf("conn-unknown")).isEmpty();

    registry.remove(CONNECTION, registry.subscriptionId(CONNECTION, "scope:a").orElseThrow());
    assertThat(registry.scopeKeysOf(CONNECTION)).containsExactly("scope:b");
  }

  @Test
  void constructorRejectsOutOfRangeLimits() {
    assertThatThrownBy(() -> new CollaborationSubscriptionRegistry(0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CollaborationSubscriptionRegistry(257))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
