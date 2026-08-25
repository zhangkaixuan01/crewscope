package io.crewscope.application.activity;

import io.crewscope.domain.activity.ActivityCategory;
import io.crewscope.domain.activity.ActivityEvent;
import io.crewscope.domain.shared.event.EventType;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.workitem.WorkItemId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Normalized filter shared by Team Activity snapshots, history and realtime cursors. */
public record ActivityFilter(
        Optional<WorkItemId> workItemId,
        Set<ActivityCategory> categories,
        Set<EventType> eventTypes,
        Set<PrincipalId> actorPrincipalIds) {

    public static final ActivityFilter ALL =
            new ActivityFilter(Optional.empty(), Set.of(), Set.of(), Set.of());

    public ActivityFilter {
        workItemId = Objects.requireNonNull(workItemId, "workItemId");
        categories = Set.copyOf(Objects.requireNonNull(categories, "categories"));
        eventTypes = Set.copyOf(Objects.requireNonNull(eventTypes, "eventTypes"));
        actorPrincipalIds =
                Set.copyOf(Objects.requireNonNull(actorPrincipalIds, "actorPrincipalIds"));
    }

    public static ActivityFilter forWorkItem(WorkItemId workItemId) {
        return new ActivityFilter(
                Optional.of(Objects.requireNonNull(workItemId, "workItemId")),
                Set.of(),
                Set.of(),
                Set.of());
    }

    /** Computes a stable fingerprint without serializing raw cursor or tenant data. */
    public ActivityFilterFingerprint fingerprint() {
        String canonical = "workItem=" + workItemId.map(WorkItemId::toString).orElse("")
                + "\ncategories=" + categories.stream()
                        .map(Enum::name)
                        .sorted()
                        .collect(Collectors.joining(","))
                + "\neventTypes=" + eventTypes.stream()
                        .map(EventType::value)
                        .sorted()
                        .collect(Collectors.joining(","))
                + "\nactors=" + actorPrincipalIds.stream()
                        .map(PrincipalId::toString)
                        .sorted()
                        .collect(Collectors.joining(","));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return new ActivityFilterFingerprint(HexFormat.of().formatHex(digest));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }

    public boolean matches(ActivityEvent event) {
        ActivityEvent required = Objects.requireNonNull(event, "event");
        return workItemId.map(required::referencesWorkItem).orElse(true)
                && (categories.isEmpty() || categories.contains(required.category()))
                && (eventTypes.isEmpty() || eventTypes.contains(required.eventType()))
                && (actorPrincipalIds.isEmpty()
                        || required.actor().principalId().filter(actorPrincipalIds::contains).isPresent());
    }
}
