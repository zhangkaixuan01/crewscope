package io.crewscope.application.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.event.PendingOutboxEvent;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.identity.ExternalIdentity;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalStatus;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.IdentityMappingConflictException;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.event.DomainEventEnvelope;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class IdentityMappingServiceTest {

  private static final OrganizationId ORGANIZATION_ID = OrganizationId.generate();
  private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-08T04:00:00Z");

  @Test
  void createsOneActiveUserAndPublishesAPrivacySafeIdentityFact() {
    Fixture fixture = new Fixture();
    IdentityMappingRequest request = request("oidc/company", "private-subject-42", "Kai");

    IdentityMappingResult result = fixture.service.map(request);

    assertTrue(result.created());
    assertEquals(PrincipalType.USER, result.principal().type());
    assertEquals(PrincipalStatus.ACTIVE, result.principal().status());
    assertEquals(PrincipalScope.organization(ORGANIZATION_ID), result.principal().scope());
    assertEquals(Optional.of(request.externalIdentity()), result.principal().externalIdentity());
    assertEquals("USER_IDENTITY_MAPPED", fixture.event.eventType().value());
    assertEquals(result.principal().id().value(), fixture.event.aggregate().id());
    assertEquals(request.correlationId(), fixture.event.correlationId());
    assertFalse(fixture.event.payload().toString().contains(request.externalIdentity().subject()));
    assertEquals(fixture.event.eventId(), fixture.outbox.domainEventId());
  }

  @Test
  void returnsTheExistingPrincipalWithoutDuplicatingTheIdentityFact() {
    Fixture fixture = new Fixture();
    IdentityMappingRequest request = request("bootstrap", "crewscope", "Administrator");

    IdentityMappingResult first = fixture.service.map(request);
    IdentityMappingResult second = fixture.service.map(request);

    assertTrue(first.created());
    assertFalse(second.created());
    assertEquals(first.principal().id(), second.principal().id());
    assertEquals(1, fixture.eventCount);
    assertEquals(1, fixture.outboxCount);
  }

  @Test
  void keepsTheSameSubjectIndependentAcrossIdentityProviders() {
    Fixture fixture = new Fixture();

    IdentityMappingResult first =
        fixture.service.map(request("oidc/company", "shared-subject", "Company User"));
    IdentityMappingResult second =
        fixture.service.map(request("oidc/partner", "shared-subject", "Partner User"));

    assertTrue(first.created());
    assertTrue(second.created());
    assertFalse(first.principal().id().equals(second.principal().id()));
    assertEquals(2, fixture.eventCount);
  }

  @Test
  void rejectsDisabledSuspendedAndArchivedAccounts() {
    for (PrincipalStatus status :
        new PrincipalStatus[] {
          PrincipalStatus.DISABLED, PrincipalStatus.SUSPENDED, PrincipalStatus.ARCHIVED
        }) {
      Fixture fixture = new Fixture();
      IdentityMappingRequest request = request("oidc/company", "account-" + status, "Account");
      fixture.repository.existing = principal(request, PrincipalType.USER, status);

      assertThrows(PolicyDeniedException.class, () -> fixture.service.map(request));
      assertEquals(0, fixture.eventCount);
    }
  }

  @Test
  void rejectsAnExternalIdentityBoundToAnIncompatiblePrincipal() {
    Fixture fixture = new Fixture();
    IdentityMappingRequest request = request("oidc/company", "conflicting-subject", "Conflict");
    fixture.repository.existing = principal(request, PrincipalType.SERVICE, PrincipalStatus.ACTIVE);

    assertThrows(IdentityMappingConflictException.class, () -> fixture.service.map(request));
    assertEquals(0, fixture.eventCount);
  }

  @Test
  void rejectsAnExternalUserIdentityBoundToTeamScope() {
    Fixture fixture = new Fixture();
    IdentityMappingRequest request = request("oidc/company", "team-scoped-subject", "Conflict");
    fixture.repository.existing =
        Principal.create(
            PrincipalId.generate(),
            PrincipalScope.team(request.organizationId(), TeamId.generate()),
            PrincipalType.USER,
            Optional.empty(),
            request.displayName(),
            Optional.of(request.externalIdentity()),
            PrincipalVisibility.TEAM,
            NOW);

    assertThrows(IdentityMappingConflictException.class, () -> fixture.service.map(request));
    assertEquals(0, fixture.eventCount);
  }

  @Test
  void rejectsAnUnknownOrganizationBeforeProvisioning() {
    Fixture fixture = new Fixture();
    fixture.repository.organizationExists = false;

    assertThrows(
        AggregateNotFoundException.class,
        () -> fixture.service.map(request("bootstrap", "crewscope", "Administrator")));
    assertEquals(0, fixture.repository.provisionCount);
  }

  private static IdentityMappingRequest request(String provider, String subject, String name) {
    return new IdentityMappingRequest(
        ORGANIZATION_ID, new ExternalIdentity(provider, subject), name, UUID.randomUUID());
  }

  private static Principal principal(
      IdentityMappingRequest request, PrincipalType type, PrincipalStatus status) {
    Principal active =
        Principal.create(
            PrincipalId.generate(),
            PrincipalScope.organization(request.organizationId()),
            type,
            Optional.empty(),
            request.displayName(),
            Optional.of(request.externalIdentity()),
            PrincipalVisibility.ORGANIZATION,
            NOW);
    if (status == PrincipalStatus.ACTIVE) {
      return active;
    }
    return active.transitionTo(status, NOW);
  }

  private static final class Fixture
      implements DomainEventStore, OutboxRepository, TransactionExecutor {
    private final InMemoryPrincipalRepository repository = new InMemoryPrincipalRepository();
    private final IdentityMappingService service =
        new IdentityMappingService(repository, this, this, this, () -> NOW);
    private DomainEventEnvelope<? extends DomainEvent> event;
    private PendingOutboxEvent outbox;
    private int eventCount;
    private int outboxCount;

    @Override
    public void append(DomainEventEnvelope<? extends DomainEvent> value) {
      event = value;
      eventCount++;
    }

    @Override
    public void enqueue(PendingOutboxEvent value) {
      outbox = value;
      outboxCount++;
    }

    @Override
    public <T> T required(Supplier<T> operation) {
      return operation.get();
    }
  }

  private static final class InMemoryPrincipalRepository implements PrincipalRepository {
    private final Map<ExternalIdentity, Principal> principals = new HashMap<>();
    private boolean organizationExists = true;
    private Principal existing;
    private int provisionCount;

    @Override
    public Optional<Principal> findById(OrganizationId organizationId, PrincipalId principalId) {
      return principals.values().stream()
          .filter(value -> value.scope().organizationId().equals(organizationId))
          .filter(value -> value.id().equals(principalId))
          .findFirst();
    }

    @Override
    public Optional<Principal> findByExternalIdentity(
        OrganizationId organizationId, String provider, String subject) {
      return Optional.ofNullable(principals.get(new ExternalIdentity(provider, subject)));
    }

    @Override
    public boolean organizationExists(OrganizationId organizationId) {
      return organizationExists && ORGANIZATION_ID.equals(organizationId);
    }

    @Override
    public PrincipalProvisioningResult provisionUser(Principal candidate) {
      provisionCount++;
      ExternalIdentity key = candidate.externalIdentity().orElseThrow();
      Principal current = existing == null ? principals.putIfAbsent(key, candidate) : existing;
      if (current == null) {
        return new PrincipalProvisioningResult(candidate, true);
      }
      principals.putIfAbsent(key, current);
      return new PrincipalProvisioningResult(current, false);
    }
  }
}
