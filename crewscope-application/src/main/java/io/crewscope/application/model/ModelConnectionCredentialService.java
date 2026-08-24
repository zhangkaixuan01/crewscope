package io.crewscope.application.model;

import io.crewscope.application.credential.CredentialAccessContext;
import io.crewscope.application.credential.CredentialCreateRequest;
import io.crewscope.application.credential.CredentialDescriptor;
import io.crewscope.application.credential.CredentialMutationContext;
import io.crewscope.application.credential.CredentialReference;
import io.crewscope.application.credential.CredentialSecret;
import io.crewscope.application.credential.CredentialStore;
import io.crewscope.application.credential.CredentialSubject;
import io.crewscope.application.credential.ResolvedCredential;
import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.command.CommandReservation;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.event.PendingOutboxEvent;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.model.ModelConnection;
import io.crewscope.domain.model.ModelConnectionHealthFailureCode;
import io.crewscope.domain.model.ModelConnectionStatus;
import io.crewscope.domain.model.ModelCredentialBinding;
import io.crewscope.domain.model.ModelCredentialSubject;
import io.crewscope.domain.model.ModelCredentialVersion;
import io.crewscope.domain.model.ModelProviderDefinition;
import io.crewscope.domain.model.ModelRegistryStatus;
import io.crewscope.domain.model.event.ModelConnectionCredentialChanged;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.event.AggregateReference;
import io.crewscope.domain.shared.event.DomainEventEnvelope;
import io.crewscope.domain.shared.event.EventActor;
import io.crewscope.domain.shared.event.EventActorType;
import io.crewscope.domain.shared.event.EventType;
import io.crewscope.domain.shared.event.SchemaVersion;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Transactional model connection and encrypted CredentialStore lifecycle orchestration. */
public final class ModelConnectionCredentialService {

    public static final String API_KEY_CREDENTIAL_TYPE = "MODEL_API_KEY";

    private final ModelConnectionRepository connectionRepository;
    private final ModelProviderDefinitionRepository providerRepository;
    private final CredentialStore credentialStore;
    private final ModelProviderHealthProbe healthProbe;
    private final DomainEventStore eventStore;
    private final OutboxRepository outboxRepository;
    private final TransactionExecutor transactionExecutor;
    private final TimeProvider timeProvider;
    private final Duration handleTimeToLive;
    private final ModelConnectionAvailabilityVerifier availabilityVerifier;

    public ModelConnectionCredentialService(
            ModelConnectionRepository connectionRepository,
            ModelProviderDefinitionRepository providerRepository,
            CredentialStore credentialStore,
            ModelProviderHealthProbe healthProbe,
            DomainEventStore eventStore,
            OutboxRepository outboxRepository,
            TransactionExecutor transactionExecutor,
            TimeProvider timeProvider,
            Duration handleTimeToLive) {
        this(
                connectionRepository,
                providerRepository,
                credentialStore,
                healthProbe,
                eventStore,
                outboxRepository,
                transactionExecutor,
                timeProvider,
                handleTimeToLive,
                ModelConnectionAvailabilityVerifier.persistedStateOnly());
    }

    public ModelConnectionCredentialService(
            ModelConnectionRepository connectionRepository,
            ModelProviderDefinitionRepository providerRepository,
            CredentialStore credentialStore,
            ModelProviderHealthProbe healthProbe,
            DomainEventStore eventStore,
            OutboxRepository outboxRepository,
            TransactionExecutor transactionExecutor,
            TimeProvider timeProvider,
            Duration handleTimeToLive,
            ModelConnectionAvailabilityVerifier availabilityVerifier) {
        this.connectionRepository = Objects.requireNonNull(connectionRepository, "connectionRepository");
        this.providerRepository = Objects.requireNonNull(providerRepository, "providerRepository");
        this.credentialStore = Objects.requireNonNull(credentialStore, "credentialStore");
        this.healthProbe = Objects.requireNonNull(healthProbe, "healthProbe");
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore");
        this.outboxRepository = Objects.requireNonNull(outboxRepository, "outboxRepository");
        this.transactionExecutor = Objects.requireNonNull(transactionExecutor, "transactionExecutor");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
        this.handleTimeToLive = requireHandleTimeToLive(handleTimeToLive);
        this.availabilityVerifier = Objects.requireNonNull(
                availabilityVerifier, "availabilityVerifier");
    }

    /** Consumes and clears the supplied plaintext after atomically creating both records. */
    public ModelConnection create(
            CreateModelConnectionCredentialCommand command, CredentialSecret plaintext) {
        CreateModelConnectionCredentialCommand required = Objects.requireNonNull(command, "command");
        try (CredentialSecret secret = Objects.requireNonNull(plaintext, "plaintext")) {
            LifecycleChange created = transactionExecutor.required(
                    () -> createInTransaction(required, secret));
            availabilityVerifier.invalidate(
                    created.connection().organizationId(), created.connection().id());
            return created.connection();
        }
    }

    /** Creates through a public idempotency gate committed with the same domain event. */
    public CommandExecution<ModelConnection> create(
            CreateModelConnectionCredentialCommand command,
            CredentialSecret plaintext,
            ModelConnectionLifecycleCommandGate gate) {
        CreateModelConnectionCredentialCommand required = Objects.requireNonNull(command, "command");
        ModelConnectionLifecycleCommandGate requiredGate = Objects.requireNonNull(gate, "gate");
        try (CredentialSecret secret = Objects.requireNonNull(plaintext, "plaintext")) {
            CommandExecution<ModelConnection> execution = transactionExecutor.required(() -> {
                UtcTimestamp now = timeProvider.now();
                CommandReservation reservation = requiredGate.reserve(now);
                if (!reservation.acquired()) {
                    return CommandExecution.replayed(reservation.receipt().orElseThrow());
                }
                LifecycleChange change = createInTransaction(required, secret);
                CommandReceipt receipt = requiredGate.complete(
                        change.domainEventId(), change.connection().version(), timeProvider.now());
                return CommandExecution.completed(change.connection(), receipt);
            });
            execution.result().ifPresent(connection ->
                    availabilityVerifier.invalidate(connection.organizationId(), connection.id()));
            return execution;
        }
    }

    /** Consumes a replacement secret and advances only the business secret revision on success. */
    public ModelConnection rotate(
            ModelConnectionCredentialCommand command, CredentialSecret replacement) {
        ModelConnectionCredentialCommand required = Objects.requireNonNull(command, "command");
        try (CredentialSecret secret = Objects.requireNonNull(replacement, "replacement")) {
            LifecycleChange rotated = transactionExecutor.required(
                    () -> rotateInTransaction(required, secret));
            availabilityVerifier.invalidate(
                    rotated.connection().organizationId(), rotated.connection().id());
            return rotated.connection();
        }
    }

    /** Rotates through a public idempotency gate without retaining plaintext in the receipt. */
    public CommandExecution<ModelConnection> rotate(
            ModelConnectionCredentialCommand command,
            CredentialSecret replacement,
            ModelConnectionLifecycleCommandGate gate) {
        ModelConnectionCredentialCommand required = Objects.requireNonNull(command, "command");
        try (CredentialSecret secret = Objects.requireNonNull(replacement, "replacement")) {
            CommandExecution<ModelConnection> execution = gated(
                    Objects.requireNonNull(gate, "gate"),
                    () -> rotateInTransaction(required, secret));
            execution.result().ifPresent(connection ->
                    availabilityVerifier.invalidate(connection.organizationId(), connection.id()));
            return execution;
        }
    }

    /** Irreversibly revokes the encrypted credential and connection in one transaction. */
    public ModelConnection revoke(RevokeModelConnectionCredentialCommand command) {
        RevokeModelConnectionCredentialCommand required = Objects.requireNonNull(command, "command");
        LifecycleChange revoked = transactionExecutor.required(() -> revokeInTransaction(required));
        availabilityVerifier.invalidate(
                revoked.connection().organizationId(), revoked.connection().id());
        return revoked.connection();
    }

    /** Revokes through a public idempotency gate committed with CredentialStore revocation. */
    public CommandExecution<ModelConnection> revoke(
            RevokeModelConnectionCredentialCommand command,
            ModelConnectionLifecycleCommandGate gate) {
        RevokeModelConnectionCredentialCommand required = Objects.requireNonNull(command, "command");
        CommandExecution<ModelConnection> execution = gated(
                Objects.requireNonNull(gate, "gate"), () -> revokeInTransaction(required));
        execution.result().ifPresent(connection ->
                availabilityVerifier.invalidate(connection.organizationId(), connection.id()));
        return execution;
    }

    /** Probes outside a database transaction, then commits a version-checked sanitized result. */
    public ModelConnection verify(ModelConnectionCredentialCommand command) {
        ModelConnectionCredentialCommand required = Objects.requireNonNull(command, "command");
        VerificationTarget target = transactionExecutor.required(() -> prepareVerification(required));
        ModelProviderHealthProbe.ProbeResult result;
        try (ProviderCredentialHandle handle = target.handle()) {
            try {
                result = healthProbe.probe(target.provider(), target.connection(), handle);
            } catch (RuntimeException ignored) {
                // Provider exception messages can contain endpoint or response data and never cross this boundary.
                result = ModelProviderHealthProbe.ProbeResult.failed(
                        ModelConnectionHealthFailureCode.PROVIDER_REJECTED);
            }
        }
        ModelProviderHealthProbe.ProbeResult sanitized = result;
        LifecycleChange verified = transactionExecutor.required(
                () -> recordVerification(required, sanitized));
        availabilityVerifier.invalidate(
                verified.connection().organizationId(), verified.connection().id());
        return verified.connection();
    }

    /** Probes outside a transaction and gates only the final versioned health commit. */
    public CommandExecution<ModelConnection> verify(
            ModelConnectionCredentialCommand command,
            ModelConnectionLifecycleCommandGate gate) {
        ModelConnectionCredentialCommand required = Objects.requireNonNull(command, "command");
        ModelConnectionLifecycleCommandGate requiredGate = Objects.requireNonNull(gate, "gate");
        Optional<CommandReceipt> replay = transactionExecutor.required(
                requiredGate::findCompletedReplay);
        if (replay.isPresent()) {
            return CommandExecution.replayed(replay.orElseThrow());
        }
        VerificationTarget target = transactionExecutor.required(() -> prepareVerification(required));
        ModelProviderHealthProbe.ProbeResult result;
        try (ProviderCredentialHandle handle = target.handle()) {
            try {
                result = healthProbe.probe(target.provider(), target.connection(), handle);
            } catch (RuntimeException ignored) {
                result = ModelProviderHealthProbe.ProbeResult.failed(
                        ModelConnectionHealthFailureCode.PROVIDER_REJECTED);
            }
        }
        ModelProviderHealthProbe.ProbeResult sanitized = result;
        CommandExecution<ModelConnection> execution = gated(
                requiredGate,
                () -> recordVerification(required, sanitized));
        execution.result().ifPresent(connection ->
                availabilityVerifier.invalidate(connection.organizationId(), connection.id()));
        return execution;
    }

    /** Suspends future selection while retaining the encrypted credential for audit and recovery. */
    public CommandExecution<ModelConnection> suspend(
            ModelConnectionCredentialCommand command,
            ModelConnectionLifecycleCommandGate gate) {
        ModelConnectionCredentialCommand required = Objects.requireNonNull(command, "command");
        CommandExecution<ModelConnection> execution = gated(
                Objects.requireNonNull(gate, "gate"), () -> suspendInTransaction(required));
        execution.result().ifPresent(connection ->
                availabilityVerifier.invalidate(connection.organizationId(), connection.id()));
        return execution;
    }

    /** Issues a metadata-only capability; each use rechecks status and exact secret revision. */
    public ProviderCredentialHandle openHandle(OpenProviderCredentialHandleRequest request) {
        OpenProviderCredentialHandleRequest required = Objects.requireNonNull(request, "request");
        return transactionExecutor.required(() -> openHandleInTransaction(required));
    }

    private LifecycleChange createInTransaction(
            CreateModelConnectionCredentialCommand command, CredentialSecret secret) {
        ModelProviderDefinition provider = requireProvider(command.providerKey());
        UtcTimestamp occurredAt = timeProvider.now();
        CredentialDescriptor descriptor = credentialStore.create(
                new CredentialCreateRequest(
                        command.credentialId(),
                        command.credentialSubject(),
                        command.credentialKey(),
                        command.providerKey().toString(),
                        Optional.of(command.connectionId().value()),
                        API_KEY_CREDENTIAL_TYPE,
                        command.credentialMetadata(),
                        command.credentialExpiresAt(),
                        command.actor()),
                secret);
        ModelConnection connection = ModelConnection.open(
                provider,
                command.connectionId(),
                command.owner(),
                command.endpoint(),
                command.region(),
                new ModelCredentialBinding(
                        descriptor.credentialId(),
                        toModelSubject(descriptor.subject()),
                        new ModelCredentialVersion(descriptor.secretVersion())),
                command.billingSubject(),
                command.actor(),
                occurredAt);
        ModelConnection registered = connectionRepository.register(connection);
        UUID eventId = appendEvent(
                registered, "CREATED", Optional.empty(), command.actor(), command.correlationId(), occurredAt);
        return new LifecycleChange(registered, eventId);
    }

    private LifecycleChange rotateInTransaction(
            ModelConnectionCredentialCommand command, CredentialSecret replacement) {
        ModelConnection connection = requireConnection(command.organizationId(), command.connectionId());
        requireExpectedVersions(connection, command.expectedConnectionVersion(), command.expectedCredentialVersion());
        CredentialAccessContext access = access(connection, command.actor(), "model:credential:rotate");
        CredentialDescriptor current = requireDescriptor(connection, access);
        CredentialDescriptor rotated = credentialStore.rotate(
                current.reference(),
                current.version(),
                new CredentialMutationContext(command.organizationId(), command.actor()),
                replacement);
        ModelCredentialVersion nextVersion = new ModelCredentialVersion(rotated.secretVersion());
        if (!command.expectedCredentialVersion().next().equals(nextVersion)) {
            throw mismatch("Credential rotation did not advance exactly one secret version");
        }
        UtcTimestamp occurredAt = timeProvider.now();
        ModelConnection updated = connectionRepository.update(connection.rotateCredential(
                command.expectedConnectionVersion(), nextVersion, command.actor(), occurredAt));
        UUID eventId = appendEvent(
                updated, "CREDENTIAL_ROTATED", Optional.empty(), command.actor(), command.correlationId(), occurredAt);
        return new LifecycleChange(updated, eventId);
    }

    private LifecycleChange revokeInTransaction(RevokeModelConnectionCredentialCommand command) {
        ModelConnection connection = requireConnection(command.organizationId(), command.connectionId());
        requireExpectedVersions(connection, command.expectedConnectionVersion(), command.expectedCredentialVersion());
        CredentialDescriptor descriptor = requireDescriptor(
                connection, access(connection, command.actor(), "model:credential:revoke"));
        CredentialDescriptor revoked = credentialStore.revoke(
                descriptor.reference(),
                descriptor.version(),
                new CredentialMutationContext(command.organizationId(), command.actor()),
                command.credentialReason());
        if (revoked.secretVersion() != command.expectedCredentialVersion().value()) {
            throw mismatch("Credential revocation changed the bound secret version");
        }
        UtcTimestamp occurredAt = timeProvider.now();
        ModelConnection updated = connectionRepository.update(connection.revoke(
                command.expectedConnectionVersion(), command.connectionReason(), command.actor(), occurredAt));
        UUID eventId = appendEvent(
                updated, "REVOKED", Optional.empty(), command.actor(), command.correlationId(), occurredAt);
        return new LifecycleChange(updated, eventId);
    }

    private LifecycleChange suspendInTransaction(ModelConnectionCredentialCommand command) {
        ModelConnection connection = requireConnection(command.organizationId(), command.connectionId());
        requireExpectedVersions(
                connection, command.expectedConnectionVersion(), command.expectedCredentialVersion());
        UtcTimestamp occurredAt = timeProvider.now();
        ModelConnection updated = connectionRepository.update(connection.suspend(
                command.expectedConnectionVersion(), command.actor(), occurredAt));
        UUID eventId = appendEvent(
                updated, "SUSPENDED", Optional.empty(), command.actor(), command.correlationId(), occurredAt);
        return new LifecycleChange(updated, eventId);
    }

    private VerificationTarget prepareVerification(ModelConnectionCredentialCommand command) {
        ModelConnection connection = requireConnection(command.organizationId(), command.connectionId());
        requireExpectedVersions(connection, command.expectedConnectionVersion(), command.expectedCredentialVersion());
        ModelProviderDefinition provider = requireProvider(connection.providerKey());
        requireUsableProviderConnection(connection, provider);
        ProviderCredentialHandle handle = newHandle(
                connection,
                command.actor(),
                "model:connection:verify",
                command.correlationId(),
                true);
        return new VerificationTarget(connection, provider, handle);
    }

    private LifecycleChange recordVerification(
            ModelConnectionCredentialCommand command, ModelProviderHealthProbe.ProbeResult result) {
        ModelConnection connection = requireConnection(command.organizationId(), command.connectionId());
        ModelProviderDefinition provider = requireProvider(connection.providerKey());
        UtcTimestamp occurredAt = timeProvider.now();
        ModelConnection verified = result.healthy()
                ? connection.recordVerificationSuccess(
                        provider,
                        command.expectedConnectionVersion(),
                        command.expectedCredentialVersion(),
                        command.actor(),
                        occurredAt)
                : connection.recordVerificationFailure(
                        provider,
                        command.expectedConnectionVersion(),
                        command.expectedCredentialVersion(),
                        result.failureCode().orElseThrow(),
                        command.actor(),
                        occurredAt);
        ModelConnection updated = connectionRepository.update(verified);
        UUID eventId = appendEvent(
                updated,
                result.healthy() ? "VERIFIED" : "VERIFICATION_FAILED",
                result.failureCode().map(Enum::name),
                command.actor(),
                command.correlationId(),
                occurredAt);
        return new LifecycleChange(updated, eventId);
    }

    private CommandExecution<ModelConnection> gated(
            ModelConnectionLifecycleCommandGate gate, LifecycleMutation mutation) {
        return transactionExecutor.required(() -> {
            CommandReservation reservation = gate.reserve(timeProvider.now());
            if (!reservation.acquired()) {
                return CommandExecution.replayed(reservation.receipt().orElseThrow());
            }
            LifecycleChange change = mutation.apply();
            CommandReceipt receipt = gate.complete(
                    change.domainEventId(), change.connection().version(), timeProvider.now());
            return CommandExecution.completed(change.connection(), receipt);
        });
    }

    private ProviderCredentialHandle openHandleInTransaction(OpenProviderCredentialHandleRequest request) {
        ModelConnection connection = requireConnection(request.organizationId(), request.connectionId());
        requireExpectedVersions(
                connection,
                request.expectedConnectionVersion(),
                request.expectedCredentialVersion());
        ModelProviderDefinition provider = requireProvider(connection.providerKey());
        requireUsableProviderConnection(connection, provider);
        return newHandle(connection, request.actor(), request.purpose(), request.correlationId(), true);
    }

    private ProviderCredentialHandle newHandle(
            ModelConnection connection,
            PrincipalId actor,
            String purpose,
            UUID correlationId,
            boolean audit) {
        CredentialAccessContext access = access(connection, actor, purpose);
        UtcTimestamp issuedAt = timeProvider.now();
        CredentialDescriptor descriptor = requireDescriptor(connection, access);
        if (!descriptor.isUsableAt(issuedAt)) {
            throw unavailable("Provider credential is unavailable");
        }
        ProviderCredentialHandle handle = new ProviderCredentialHandle(
                connection.id(),
                connection.credentialBinding().credentialVersion(),
                issuedAt,
                handleTimeToLive,
                timeProvider,
                (connectionId, version) -> resolveForHandle(
                        connection.organizationId(), connectionId, version, access));
        if (audit) {
            appendEvent(connection, "HANDLE_ISSUED", Optional.empty(), actor, correlationId, issuedAt);
        }
        return handle;
    }

    private ResolvedCredential resolveForHandle(
            io.crewscope.domain.shared.id.OrganizationId organizationId,
            io.crewscope.domain.model.ModelConnectionId connectionId,
            ModelCredentialVersion expectedCredentialVersion,
            CredentialAccessContext access) {
        ModelConnection current = requireConnection(organizationId, connectionId);
        ModelProviderDefinition provider = requireProvider(current.providerKey());
        if (!isUsableProviderConnection(current, provider)
                || !current.credentialBinding().credentialVersion().equals(expectedCredentialVersion)) {
            throw unavailable("Provider credential handle is no longer valid");
        }
        CredentialReference reference = new CredentialReference(
                organizationId, current.credentialBinding().credentialId());
        ResolvedCredential resolved = credentialStore.resolve(reference, access)
                .orElseThrow(() -> unavailable("Provider credential is unavailable"));
        try {
            validateDescriptor(current, resolved.descriptor());
            return resolved;
        } catch (RuntimeException exception) {
            resolved.close();
            throw exception;
        }
    }

    private CredentialDescriptor requireDescriptor(
            ModelConnection connection, CredentialAccessContext access) {
        CredentialReference reference = new CredentialReference(
                connection.organizationId(), connection.credentialBinding().credentialId());
        CredentialDescriptor descriptor = credentialStore.describe(reference, access)
                .orElseThrow(() -> new ModelConnectionCredentialException(
                        ModelConnectionCredentialException.Error.CREDENTIAL_NOT_FOUND,
                        "Model connection credential was not found"));
        validateDescriptor(connection, descriptor);
        return descriptor;
    }

    private static void validateDescriptor(
            ModelConnection connection, CredentialDescriptor descriptor) {
        boolean matches = descriptor.credentialId().equals(connection.credentialBinding().credentialId())
                && descriptor.subject().equals(toCredentialSubject(connection.credentialBinding().subject()))
                && descriptor.providerKey().equals(connection.providerKey().toString())
                && descriptor.connectionRef().filter(connection.id().value()::equals).isPresent()
                && descriptor.credentialType().equals(API_KEY_CREDENTIAL_TYPE)
                && descriptor.secretVersion() == connection.credentialBinding().credentialVersion().value();
        if (!matches) {
            throw mismatch("Model connection credential binding is inconsistent");
        }
    }

    private ModelConnection requireConnection(
            io.crewscope.domain.shared.id.OrganizationId organizationId,
            io.crewscope.domain.model.ModelConnectionId connectionId) {
        return connectionRepository.findById(organizationId, connectionId)
                .orElseThrow(() -> new ModelConnectionCredentialException(
                        ModelConnectionCredentialException.Error.CONNECTION_NOT_FOUND,
                        "Model connection was not found"));
    }

    private ModelProviderDefinition requireProvider(io.crewscope.domain.model.ModelProviderKey key) {
        return providerRepository.findByKey(key)
                .orElseThrow(() -> new ModelConnectionCredentialException(
                        ModelConnectionCredentialException.Error.PROVIDER_NOT_FOUND,
                        "Model provider was not found"));
    }

    private static void requireUsableProviderConnection(
            ModelConnection connection, ModelProviderDefinition provider) {
        if (!isUsableProviderConnection(connection, provider)) {
            throw unavailable("Model provider connection is unavailable");
        }
    }

    private static boolean isUsableProviderConnection(
            ModelConnection connection, ModelProviderDefinition provider) {
        return connection.status() == ModelConnectionStatus.ACTIVE
                && provider.status() == ModelRegistryStatus.ACTIVE;
    }

    private static void requireExpectedVersions(
            ModelConnection connection,
            long expectedConnectionVersion,
            ModelCredentialVersion expectedCredentialVersion) {
        if (connection.version() != expectedConnectionVersion) {
            throw new OptimisticLockConflictException(
                    "ModelConnection", connection.id(), expectedConnectionVersion, connection.version());
        }
        if (!connection.credentialBinding().credentialVersion().equals(expectedCredentialVersion)) {
            throw mismatch("Model connection credential version changed");
        }
    }

    private static CredentialAccessContext access(
            ModelConnection connection, PrincipalId actor, String purpose) {
        return new CredentialAccessContext(
                connection.organizationId(),
                actor,
                Set.of(connection.credentialBinding().credentialId()),
                purpose);
    }

    private UUID appendEvent(
            ModelConnection connection,
            String operation,
            Optional<String> failureCode,
            PrincipalId actor,
            UUID correlationId,
            UtcTimestamp occurredAt) {
        UUID eventId = UUID.randomUUID();
        DomainEventEnvelope<ModelConnectionCredentialChanged> event = new DomainEventEnvelope<>(
                eventId,
                EventType.from("MODEL_CONNECTION_" + operation),
                SchemaVersion.V1,
                connection.organizationId(),
                connection.owner().teamId(),
                Optional.empty(),
                AggregateReference.of("MODEL_CONNECTION", connection.id()),
                connection.version(),
                EventActor.principal(EventActorType.USER, actor),
                correlationId,
                Optional.empty(),
                Optional.empty(),
                occurredAt,
                new ModelConnectionCredentialChanged(
                        connection.id().value(),
                        operation,
                        connection.providerKey().toString(),
                        connection.credentialBinding().credentialVersion().value(),
                        connection.status().name(),
                        failureCode));
        eventStore.append(event);
        outboxRepository.enqueue(PendingOutboxEvent.fromDomain(UUID.randomUUID(), event));
        return eventId;
    }

    private static ModelCredentialSubject toModelSubject(CredentialSubject subject) {
        return switch (subject.type()) {
            case ORGANIZATION -> ModelCredentialSubject.organization(subject.organizationId());
            case TEAM -> ModelCredentialSubject.team(subject.organizationId(), subject.teamId().orElseThrow());
            case PRINCIPAL -> ModelCredentialSubject.principal(
                    subject.organizationId(), subject.principalId().orElseThrow());
        };
    }

    private static CredentialSubject toCredentialSubject(ModelCredentialSubject subject) {
        return switch (subject.type()) {
            case ORGANIZATION -> CredentialSubject.organization(subject.organizationId());
            case TEAM -> CredentialSubject.team(subject.organizationId(), subject.teamId().orElseThrow());
            case PRINCIPAL -> CredentialSubject.principal(
                    subject.organizationId(), subject.principalId().orElseThrow());
        };
    }

    private static Duration requireHandleTimeToLive(Duration value) {
        Duration ttl = Objects.requireNonNull(value, "handleTimeToLive");
        if (ttl.isZero() || ttl.isNegative() || ttl.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalArgumentException("handleTimeToLive must be between 1 nanosecond and 10 minutes");
        }
        return ttl;
    }

    private static ModelConnectionCredentialException mismatch(String message) {
        return new ModelConnectionCredentialException(
                ModelConnectionCredentialException.Error.CREDENTIAL_MISMATCH, message);
    }

    private static ModelConnectionCredentialException unavailable(String message) {
        return new ModelConnectionCredentialException(
                ModelConnectionCredentialException.Error.CREDENTIAL_UNAVAILABLE, message);
    }

    private record VerificationTarget(
            ModelConnection connection,
            ModelProviderDefinition provider,
            ProviderCredentialHandle handle) {}

    private record LifecycleChange(ModelConnection connection, UUID domainEventId) {}

    @FunctionalInterface
    private interface LifecycleMutation {
        LifecycleChange apply();
    }
}
