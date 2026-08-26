package io.crewscope.application.operations;

import io.crewscope.application.projection.ProjectionAdministration;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/** Authorizes, deduplicates and dispatches closed-set recovery commands inside one transaction. */
public final class OperationsRecoveryService {

    private final ProjectionAdministration administration;
    private final OperationsRecoveryRepository repository;
    private final TransactionExecutor transactions;
    private final TimeProvider timeProvider;

    public OperationsRecoveryService(
            ProjectionAdministration administration,
            OperationsRecoveryRepository repository,
            TransactionExecutor transactions,
            TimeProvider timeProvider) {
        this.administration = Objects.requireNonNull(administration, "administration");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
    }

    public OperationsRecoveryResult recover(OperationsRecoveryCommand command) {
        OperationsRecoveryCommand required = Objects.requireNonNull(command, "command");
        OperationsRecoveryFingerprint fingerprint = fingerprint(required);
        return transactions.required(() -> recoverInTransaction(required, fingerprint));
    }

    private OperationsRecoveryResult recoverInTransaction(
            OperationsRecoveryCommand command, OperationsRecoveryFingerprint fingerprint) {
        UtcTimestamp now = timeProvider.now();
        administration.requireOrganizationAdministrator(
                command.organizationId(), command.actor(), now);
        Optional<OperationsRecoveryReceipt> existing = repository.findReceipt(
                command.organizationId(), command.commandId());
        if (existing.isPresent()) {
            return existing.orElseThrow().replay(fingerprint);
        }
        OperationsRecoveryRequest request = new OperationsRecoveryRequest(
                command.commandId(),
                command.organizationId(),
                command.target(),
                command.actor().id(),
                fingerprint,
                now);
        OperationsRecoveryReceipt receipt = Objects.requireNonNull(
                repository.recover(request), "operations recovery receipt");
        requireMatchingReceipt(request, receipt);
        return receipt.replay(fingerprint);
    }

    private static void requireMatchingReceipt(
            OperationsRecoveryRequest request, OperationsRecoveryReceipt receipt) {
        if (!receipt.commandId().equals(request.commandId())
                || !receipt.organizationId().equals(request.organizationId())
                || receipt.result().action() != request.target().action()
                || !receipt.result().targetReferenceHash().equals(
                        request.target().referenceHash())) {
            throw new IllegalStateException("operations recovery repository returned mixed scope");
        }
    }

    private static OperationsRecoveryFingerprint fingerprint(OperationsRecoveryCommand command) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, command.organizationId().toString());
            update(digest, command.actor().id().toString());
            for (String coordinate : command.target().fingerprintCoordinates()) {
                update(digest, coordinate);
            }
            return new OperationsRecoveryFingerprint(HexFormat.of().formatHex(digest.digest()));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }
}
