package io.crewscope.application.identity;

import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.event.PendingOutboxEvent;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.identity.event.UserIdentityMapped;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.IdentityMappingConflictException;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.event.AggregateReference;
import io.crewscope.domain.shared.event.DomainEventEnvelope;
import io.crewscope.domain.shared.event.EventActor;
import io.crewscope.domain.shared.event.EventActorType;
import io.crewscope.domain.shared.event.EventType;
import io.crewscope.domain.shared.event.SchemaVersion;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Maps trusted Bootstrap and OIDC subjects to durable organization-scoped USER Principals. */
public final class IdentityMappingService {

  private final PrincipalRepository principalRepository;
  private final DomainEventStore domainEventStore;
  private final OutboxRepository outboxRepository;
  private final TransactionExecutor transactionExecutor;
  private final TimeProvider timeProvider;

  public IdentityMappingService(
      PrincipalRepository principalRepository,
      DomainEventStore domainEventStore,
      OutboxRepository outboxRepository,
      TransactionExecutor transactionExecutor,
      TimeProvider timeProvider) {
    this.principalRepository = Objects.requireNonNull(principalRepository, "principalRepository");
    this.domainEventStore = Objects.requireNonNull(domainEventStore, "domainEventStore");
    this.outboxRepository = Objects.requireNonNull(outboxRepository, "outboxRepository");
    this.transactionExecutor = Objects.requireNonNull(transactionExecutor, "transactionExecutor");
    this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
  }

  /**
   * Resolves or creates one Principal. Team membership remains an explicit Team use-case decision
   * and is never inferred from an authenticated request path.
   */
  public IdentityMappingResult map(IdentityMappingRequest request) {
    IdentityMappingRequest required = Objects.requireNonNull(request, "request");
    return transactionExecutor.required(() -> mapInTransaction(required));
  }

  private IdentityMappingResult mapInTransaction(IdentityMappingRequest request) {
    if (!principalRepository.organizationExists(request.organizationId())) {
      throw new AggregateNotFoundException("Organization", request.organizationId());
    }
    UtcTimestamp occurredAt = timeProvider.now();
    Principal candidate =
        Principal.create(
            PrincipalId.generate(),
            PrincipalScope.organization(request.organizationId()),
            PrincipalType.USER,
            Optional.empty(),
            request.displayName(),
            Optional.of(request.externalIdentity()),
            PrincipalVisibility.ORGANIZATION,
            occurredAt);
    PrincipalProvisioningResult provisioned = principalRepository.provisionUser(candidate);
    Principal principal = requireCompatibleMapping(request, provisioned.principal());
    if (!principal.canAct()) {
      throw new PolicyDeniedException("act with this account");
    }
    if (provisioned.created()) {
      appendIdentityMappedEvent(principal, request, occurredAt);
    }
    return new IdentityMappingResult(principal, provisioned.created());
  }

  private static Principal requireCompatibleMapping(
      IdentityMappingRequest request, Principal principal) {
    boolean compatible =
        principal.scope().organizationId().equals(request.organizationId())
            && principal.scope().teamId().isEmpty()
            && principal.type() == PrincipalType.USER
            && principal.externalIdentity().filter(request.externalIdentity()::equals).isPresent();
    if (!compatible) {
      // Do not include the external subject in the exception: it may contain private identity data.
      throw new IdentityMappingConflictException(request.externalIdentity().provider());
    }
    return principal;
  }

  private void appendIdentityMappedEvent(
      Principal principal, IdentityMappingRequest request, UtcTimestamp occurredAt) {
    UUID eventId = UUID.randomUUID();
    DomainEventEnvelope<UserIdentityMapped> event =
        new DomainEventEnvelope<>(
            eventId,
            EventType.from("USER_IDENTITY_MAPPED"),
            SchemaVersion.V1,
            request.organizationId(),
            Optional.empty(),
            Optional.empty(),
            AggregateReference.of("PRINCIPAL", principal.id()),
            principal.version(),
            EventActor.principal(EventActorType.USER, principal.id()),
            request.correlationId(),
            Optional.empty(),
            Optional.empty(),
            occurredAt,
            new UserIdentityMapped(request.externalIdentity().provider()));
    domainEventStore.append(event);
    outboxRepository.enqueue(PendingOutboxEvent.fromDomain(UUID.randomUUID(), event));
  }
}
