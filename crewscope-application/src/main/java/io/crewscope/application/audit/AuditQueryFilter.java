package io.crewscope.application.audit;

import io.crewscope.domain.audit.AuditEventCategory;
import io.crewscope.domain.audit.AuditOutcome;
import io.crewscope.domain.audit.AuditQueryEvent;
import io.crewscope.domain.provider.ProviderBindingId;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.event.AggregateReference;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Normalized combination filter shared by Audit history, Correlation lookup and export. */
public record AuditQueryFilter(
        Optional<UtcTimestamp> occurredFrom,
        Optional<UtcTimestamp> occurredBefore,
        Set<AuditEventCategory> categories,
        Set<AuditOutcome> outcomes,
        Set<PrincipalId> initiatorIds,
        Set<PrincipalId> actorIds,
        Set<PrincipalId> agentPrincipalIds,
        Optional<AggregateReference> subject,
        Optional<ProviderBindingId> providerBindingId,
        Optional<UUID> correlationId) {

    public static final int MAX_VALUES_PER_FILTER = 50;

    public static final AuditQueryFilter ALL = new AuditQueryFilter(
            Optional.empty(),
            Optional.empty(),
            Set.of(),
            Set.of(),
            Set.of(),
            Set.of(),
            Set.of(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty());

    public AuditQueryFilter {
        occurredFrom = Objects.requireNonNull(occurredFrom, "occurredFrom");
        occurredBefore = Objects.requireNonNull(occurredBefore, "occurredBefore");
        categories = copyBounded(categories, "categories");
        outcomes = copyBounded(outcomes, "outcomes");
        initiatorIds = copyBounded(initiatorIds, "initiatorIds");
        actorIds = copyBounded(actorIds, "actorIds");
        agentPrincipalIds = copyBounded(agentPrincipalIds, "agentPrincipalIds");
        subject = Objects.requireNonNull(subject, "subject");
        providerBindingId = Objects.requireNonNull(providerBindingId, "providerBindingId");
        correlationId = Objects.requireNonNull(correlationId, "correlationId");
        correlationId.ifPresent(value ->
                io.crewscope.domain.shared.id.AggregateId.requireValue(
                        value, "AuditQueryFilter.correlationId"));
        if (occurredFrom.isPresent()
                && occurredBefore.isPresent()
                && occurredFrom.orElseThrow().compareTo(occurredBefore.orElseThrow()) >= 0) {
            throw new DomainValidationException(
                    "auditQuery.occurredAt", "from must be before the exclusive upper bound");
        }
    }

    /** Computes a stable scope fingerprint without exposing raw filter data in a cursor token. */
    public AuditFilterFingerprint fingerprint() {
        String canonical = "audit-filter-v1"
                + "\nfrom=" + occurredFrom.map(Object::toString).orElse("")
                + "\nbefore=" + occurredBefore.map(Object::toString).orElse("")
                + "\ncategories=" + enumNames(categories)
                + "\noutcomes=" + enumNames(outcomes)
                + "\ninitiators=" + identifiers(initiatorIds)
                + "\nactors=" + identifiers(actorIds)
                + "\nagents=" + identifiers(agentPrincipalIds)
                + "\nsubject=" + subject
                        .map(value -> value.type() + ":" + value.id())
                        .orElse("")
                + "\nproviderBinding=" + providerBindingId.map(Object::toString).orElse("")
                + "\ncorrelation=" + correlationId.map(UUID::toString).orElse("");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return new AuditFilterFingerprint(HexFormat.of().formatHex(digest));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }

    public boolean matches(AuditQueryEvent event) {
        AuditQueryEvent required = Objects.requireNonNull(event, "event");
        return occurredFrom.map(value -> required.occurredAt().compareTo(value) >= 0).orElse(true)
                && occurredBefore.map(value -> required.occurredAt().compareTo(value) < 0).orElse(true)
                && (categories.isEmpty() || categories.contains(required.category()))
                && (outcomes.isEmpty() || outcomes.contains(required.outcome()))
                && (initiatorIds.isEmpty()
                        || required.identity().initiatorId().filter(initiatorIds::contains).isPresent())
                && (actorIds.isEmpty()
                        || required.identity().actor().id().filter(actorIds::contains).isPresent())
                && (agentPrincipalIds.isEmpty()
                        || required.identity().agentPrincipalId()
                                .filter(agentPrincipalIds::contains)
                                .isPresent())
                && subject.map(required.subject()::equals).orElse(true)
                && providerBindingId.map(expected -> required.providerReference()
                                .map(reference -> reference.providerBindingId().equals(expected))
                                .orElse(false))
                        .orElse(true)
                && correlationId
                        .map(required.correlation().correlationId()::equals)
                        .orElse(true);
    }

    private static <T> Set<T> copyBounded(Set<T> values, String name) {
        Set<T> copy = Set.copyOf(Objects.requireNonNull(values, name));
        if (copy.size() > MAX_VALUES_PER_FILTER) {
            throw new DomainValidationException(
                    "auditQuery." + name,
                    "must contain at most " + MAX_VALUES_PER_FILTER + " values");
        }
        return copy;
    }

    private static String enumNames(Set<? extends Enum<?>> values) {
        return values.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
    }

    private static String identifiers(Set<PrincipalId> values) {
        return values.stream().map(Object::toString).sorted().collect(Collectors.joining(","));
    }
}
