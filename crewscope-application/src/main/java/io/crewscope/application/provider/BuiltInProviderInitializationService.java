package io.crewscope.application.provider;

import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.provider.ProviderBinding;
import io.crewscope.domain.provider.ProviderBindingTarget;
import io.crewscope.domain.provider.ProviderConnectionRequirement;
import io.crewscope.domain.provider.ProviderDefinition;
import io.crewscope.domain.provider.ProviderImplementation;
import io.crewscope.domain.provider.ProviderOwner;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.Team;
import io.crewscope.domain.workspace.Workspace;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Idempotently registers one built-in Provider and one default Binding for every Team Workspace. */
public final class BuiltInProviderInitializationService {

  private final BuiltInProviderRegistration registration;
  private final ProviderDefinitionRepository definitionRepository;
  private final ProviderImplementationRepository implementationRepository;
  private final ProviderBindingRepository bindingRepository;
  private final ProviderBootstrapLock bootstrapLock;
  private final TransactionExecutor transactionExecutor;
  private final TimeProvider timeProvider;

  public BuiltInProviderInitializationService(
      BuiltInProviderRegistration registration,
      ProviderDefinitionRepository definitionRepository,
      ProviderImplementationRepository implementationRepository,
      ProviderBindingRepository bindingRepository,
      ProviderBootstrapLock bootstrapLock,
      TransactionExecutor transactionExecutor,
      TimeProvider timeProvider) {
    this.registration = Objects.requireNonNull(registration, "registration");
    this.definitionRepository =
        Objects.requireNonNull(definitionRepository, "definitionRepository");
    this.implementationRepository =
        Objects.requireNonNull(implementationRepository, "implementationRepository");
    this.bindingRepository = Objects.requireNonNull(bindingRepository, "bindingRepository");
    this.bootstrapLock = Objects.requireNonNull(bootstrapLock, "bootstrapLock");
    this.transactionExecutor = Objects.requireNonNull(transactionExecutor, "transactionExecutor");
    this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
  }

  public ProviderFoundation initialize(
      Team team, Workspace defaultWorkspace, Principal actor) {
    Team requiredTeam = Objects.requireNonNull(team, "team");
    Workspace requiredWorkspace = Objects.requireNonNull(defaultWorkspace, "defaultWorkspace");
    Principal requiredActor = Objects.requireNonNull(actor, "actor");
    return transactionExecutor.required(
        () -> initializeInTransaction(requiredTeam, requiredWorkspace, requiredActor));
  }

  private ProviderFoundation initializeInTransaction(
      Team team, Workspace workspace, Principal actor) {
    ProviderBindingTarget target = ProviderBindingTarget.workspace(workspace);
    ProviderOwner owner = ProviderOwner.team(team);
    if (!target.teamId().equals(team.id())
        || !target.organizationId().equals(team.organizationId())
        || !workspace.id().equals(team.defaultWorkspaceId())) {
      throw new DomainValidationException(
          "providerBinding.target", "must be the Team default Workspace");
    }
    bootstrapLock.acquire(team.organizationId());
    UtcTimestamp now = timeProvider.now();
    ProviderDefinition definition = definition(team, actor, now);
    ProviderImplementation implementation = implementation(definition, actor, now);
    ProviderBinding binding = binding(team, target, owner, definition, implementation, actor, now);
    return new ProviderFoundation(definition, implementation, binding);
  }

  private ProviderDefinition definition(Team team, Principal actor, UtcTimestamp now) {
    ProviderDefinition candidate =
        ProviderDefinition.create(
            registration.definitionId(team.organizationId()),
            team.organizationId(),
            registration.definitionKey(),
            registration.type(),
            registration.interfaceVersion(),
            registration.displayName(),
            registration.capabilities(),
            actor,
            now);
    ProviderDefinition committed =
        definitionRepository
            .findByKey(team.organizationId(), registration.definitionKey())
            .orElseGet(() -> definitionRepository.create(candidate));
    if (!committed.id().equals(candidate.id())
        || !committed.organizationId().equals(candidate.organizationId())
        || !committed.key().equals(candidate.key())
        || committed.type() != candidate.type()
        || !committed.interfaceVersion().equals(candidate.interfaceVersion())
        || !committed.displayName().equals(candidate.displayName())
        || !committed.capabilities().equals(candidate.capabilities())) {
      throw incompatible("ProviderDefinition");
    }
    return committed;
  }

  private ProviderImplementation implementation(
      ProviderDefinition definition, Principal actor, UtcTimestamp now) {
    ProviderImplementation candidate =
        ProviderImplementation.create(
            registration.implementationId(definition.organizationId()),
            definition,
            registration.implementationKey(),
            registration.implementationVersion(),
            registration.capabilities(),
            ProviderConnectionRequirement.NONE,
            Optional.empty(),
            actor,
            now);
    List<ProviderImplementation> sameKey =
        implementationRepository.findByDefinition(definition.organizationId(), definition.id())
            .stream()
            .filter(value -> value.key().equals(registration.implementationKey()))
            .toList();
    ProviderImplementation committed =
        sameKey.isEmpty() ? implementationRepository.create(candidate) : sameKey.get(0);
    if (sameKey.size() > 1
        || !committed.id().equals(candidate.id())
        || !committed.organizationId().equals(candidate.organizationId())
        || !committed.definitionId().equals(candidate.definitionId())
        || committed.type() != candidate.type()
        || !committed.definitionInterfaceVersion().equals(candidate.definitionInterfaceVersion())
        || !committed.key().equals(candidate.key())
        || !committed.implementationVersion().equals(candidate.implementationVersion())
        || !committed.capabilities().equals(candidate.capabilities())
        || committed.connectionRequirement() != ProviderConnectionRequirement.NONE
        || committed.connectorKey().isPresent()) {
      throw incompatible("ProviderImplementation");
    }
    return committed;
  }

  private ProviderBinding binding(
      Team team,
      ProviderBindingTarget target,
      ProviderOwner owner,
      ProviderDefinition definition,
      ProviderImplementation implementation,
      Principal actor,
      UtcTimestamp now) {
    ProviderBinding candidate =
        ProviderBinding.bind(
            registration.workspaceBindingId(team.organizationId(), team.id()),
            target,
            owner,
            definition,
            implementation,
            Optional.empty(),
            Optional.empty(),
            registration.workspaceAccess(target.workspaceId()),
            true,
            actor,
            now);
    ProviderBinding committed =
        bindingRepository
            .findById(team.organizationId(), candidate.id())
            .orElseGet(() -> bindingRepository.create(candidate));
    if (!committed.id().equals(candidate.id())
        || !committed.organizationId().equals(candidate.organizationId())
        || !committed.target().equals(candidate.target())
        || !committed.owner().equals(candidate.owner())
        || !committed.definitionId().equals(candidate.definitionId())
        || committed.definitionVersion() != candidate.definitionVersion()
        || committed.providerType() != candidate.providerType()
        || !committed.implementationId().equals(candidate.implementationId())
        || committed.implementationVersion() != candidate.implementationVersion()
        || committed.connectionId().isPresent()
        || committed.connectionGrantId().isPresent()
        || committed.executionIdentity().isPresent()
        || !committed.effectiveAccess().equals(candidate.effectiveAccess())
        || !committed.defaultUsage()) {
      throw incompatible("ProviderBinding");
    }
    return committed;
  }

  private static DomainValidationException incompatible(String aggregate) {
    return new DomainValidationException(
        "builtInProvider.registration", aggregate + " conflicts with the product contract");
  }
}
