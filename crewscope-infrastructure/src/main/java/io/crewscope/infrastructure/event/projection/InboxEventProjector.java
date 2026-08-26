package io.crewscope.infrastructure.event.projection;

import io.crewscope.application.inbox.InboxEventTypeDefinition;
import io.crewscope.application.inbox.InboxEventTypeRegistry;
import io.crewscope.application.inbox.InboxProjectionOperation;
import io.crewscope.domain.inbox.InboxCloseReason;
import io.crewscope.domain.inbox.InboxItem;
import io.crewscope.domain.inbox.InboxItemId;
import io.crewscope.domain.inbox.InboxItemType;
import io.crewscope.domain.inbox.InboxPriority;
import io.crewscope.domain.inbox.InboxSource;
import io.crewscope.domain.inbox.InboxSourceKey;
import io.crewscope.domain.inbox.InboxSourceRevision;
import io.crewscope.domain.inbox.InboxSourceType;
import io.crewscope.domain.projection.ProjectionCanonicalHash;
import io.crewscope.domain.projection.ProjectionDefinition;
import io.crewscope.domain.projection.ProjectionDefinitionVersion;
import io.crewscope.domain.projection.ProjectionGenerationKey;
import io.crewscope.domain.projection.ProjectionGenerationLease;
import io.crewscope.domain.projection.ProjectionName;
import io.crewscope.domain.projection.ProjectionSnapshot;
import io.crewscope.domain.shared.event.EventType;
import io.crewscope.domain.shared.event.SchemaVersion;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamMemberId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Projects the five member Inbox source families without copying unreviewed event payloads. */
@Component
public class InboxEventProjector implements GenerationAwareProjectionHandler {

    public static final ProjectionName PROJECTION_NAME = new ProjectionName("member-inbox");
    public static final ProjectionDefinition DEFINITION = new ProjectionDefinition(
            PROJECTION_NAME,
            ProjectionDefinitionVersion.V1,
            SchemaVersion.V1,
            "inbox.canonical-v1",
            "inbox.expected-v1");

    private static final Set<String> ACTION_EXCEPTION_OPEN =
            Set.of("MANUAL_REVIEW", "FAILED", "MANUALLY_FAILED");
    private static final Set<String> ACTION_EXCEPTION_RESOLVED =
            Set.of("SUCCEEDED", "MANUALLY_SUCCEEDED", "CANCELLED");

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final InboxEventTypeRegistry registry;
    private final NotificationIntentProjector notificationProjector;

    public InboxEventProjector(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            InboxEventTypeRegistry registry,
            NotificationIntentProjector notificationProjector) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.notificationProjector = Objects.requireNonNull(
                notificationProjector, "notificationProjector");
    }

    @Override
    public ProjectionDefinition definition() {
        return DEFINITION;
    }

    @Override
    public void project(ProjectionGenerationLease lease, ProjectionEvent event) {
        ProjectionGenerationLease target = Objects.requireNonNull(lease, "lease");
        ProjectionEvent source = Objects.requireNonNull(event, "event");
        if (!target.key().organizationId().value().equals(source.organizationId())) {
            throw new IllegalArgumentException("Inbox lease and event Organization do not match");
        }
        List<InboxMutation> mutations = mutations(source, false);
        for (InboxMutation mutation : mutations) {
            apply(target, mutation);
        }
        source.teamId().ifPresent(teamId -> {
            TeamId team = new TeamId(teamId);
            closeIneligible(target, team);
            notificationProjector.reconcileTeam(target, team, source.occurredAt());
        });
    }

    /** Reconciles membership changes even before a dedicated Team membership event is introduced. */
    public int reconcileCurrentEligibility(
            ProjectionGenerationLease lease, TeamId teamId) {
        return closeIneligible(
                Objects.requireNonNull(lease, "lease"),
                Objects.requireNonNull(teamId, "teamId"));
    }

    @Override
    public ProjectionSnapshot expectedSnapshot(OrganizationId organizationId) {
        OrganizationId organization = Objects.requireNonNull(organizationId, "organizationId");
        Map<InboxItemId, InboxItem> expected = new LinkedHashMap<>();
        List<ProjectionEvent> history = jdbc.query(
                """
                SELECT event_id, event_type, schema_version, organization_id, team_id,
                       workspace_id, subject_type, subject_id, aggregate_version,
                       actor_type, actor_id, correlation_id, causation_id, occurred_at,
                       payload::TEXT AS payload
                FROM crewscope.domain_event
                WHERE organization_id = ?
                ORDER BY occurred_at, event_id
                """,
                (row, ignored) -> historyEvent(row),
                organization.value());
        for (ProjectionEvent event : history) {
            for (InboxMutation mutation : mutations(event, true)) {
                mergeExpected(expected, mutation);
            }
        }
        closeExpectedIneligible(organization, expected);
        return snapshot(expected.values().stream()
                .map(this::canonical)
                .sorted()
                .toList());
    }

    @Override
    public ProjectionSnapshot actualSnapshot(ProjectionGenerationKey generationKey) {
        ProjectionGenerationKey generation = Objects.requireNonNull(
                generationKey, "generationKey");
        List<String> rows = jdbc.query(
                """
                SELECT organization_id, team_id, member_id, projection_name, generation,
                       inbox_item_id, projection_schema_version, item_type, source_type,
                       source_id, source_revision, priority, deadline, opened_at,
                       source_status, close_reason, closed_at
                FROM crewscope.inbox_item
                WHERE organization_id = ? AND projection_name = ? AND generation = ?
                ORDER BY inbox_item_id
                """,
                (row, ignored) -> canonical(readItem(row)),
                generation.organizationId().value(),
                generation.projectionName().value(),
                generation.generation().value());
        return snapshot(rows);
    }

    private List<InboxMutation> mutations(ProjectionEvent event, boolean replay) {
        Optional<InboxEventTypeDefinition> registered = registry.find(
                EventType.from(event.eventType()), SchemaVersion.from(event.schemaVersion()));
        if (registered.isEmpty()) {
            return List.of();
        }
        InboxEventTypeDefinition definition = registered.orElseThrow();
        JsonNode payload = payload(event.payloadJson());
        definition.requiredPayloadFields().forEach(field -> scalar(payload, field, true));
        return switch (definition.operation()) {
            case RESPONSIBILITY_ASSIGNED -> responsibilityAssigned(event, payload, replay);
            case RESPONSIBILITY_RELEASED -> List.of(responsibility(
                    event, event.aggregateId(), replay,
                    Optional.of(InboxCloseReason.RESPONSIBILITY_RELEASED), event.occurredAt()));
            case REVIEW_OPENED -> List.of(review(event, payload, replay, Optional.empty()));
            case REVIEW_COMPLETED -> List.of(review(
                    event, payload, replay,
                    Optional.of(InboxCloseReason.REVIEW_COMPLETED)));
            case REVIEW_SUPERSEDED -> List.of(review(
                    event, payload, replay,
                    Optional.of(InboxCloseReason.REVIEW_SUPERSEDED)));
            case CONFIRMATION_OPENED -> List.of(confirmation(
                    event, payload, replay, Optional.empty()));
            case CONFIRMATION_COMPLETED -> List.of(confirmation(
                    event, payload, replay,
                    Optional.of(InboxCloseReason.CONFIRMATION_COMPLETED)));
            case CONFIRMATION_CANCELLED -> List.of(confirmation(
                    event, payload, replay,
                    Optional.of(InboxCloseReason.CONFIRMATION_CANCELLED)));
            case TASK_EXCEPTION_OPENED -> List.of(taskException(
                    event, payload, replay, Optional.empty()));
            case TASK_EXCEPTION_RESOLVED -> List.of(taskException(
                    event, payload, replay,
                    Optional.of(InboxCloseReason.EXCEPTION_RESOLVED)));
            case ACTION_DELIVERY_REFRESHED -> actionDelivery(event, payload, replay);
        };
    }

    private List<InboxMutation> responsibilityAssigned(
            ProjectionEvent event, JsonNode payload, boolean replay) {
        List<InboxMutation> result = new ArrayList<>();
        Optional<UUID> replaced = uuid(payload, "replacedAssignmentId", false);
        replaced.ifPresent(id -> result.add(responsibility(
                event, id, replay,
                Optional.of(InboxCloseReason.RESPONSIBILITY_REPLACED), event.occurredAt())));
        result.add(responsibility(event, event.aggregateId(), replay, Optional.empty(), event.occurredAt()));
        return List.copyOf(result);
    }

    private InboxMutation responsibility(
            ProjectionEvent event,
            UUID assignmentId,
            boolean replay,
            Optional<InboxCloseReason> forcedReason,
            UtcTimestamp forcedAt) {
        AuthorityRow row = one(jdbc.query(
                """
                SELECT assignment.team_id, assignment.actor_member_id AS member_id,
                       assignment.role AS kind, assignment.status,
                       assignment.accepted_at AS opened_at,
                       assignment.released_at AS terminal_at,
                       member.status AS member_status, member.updated_at AS member_updated_at
                FROM crewscope.responsibility_assignment assignment
                JOIN crewscope.team_member member
                  ON member.organization_id = assignment.organization_id
                 AND member.team_id = assignment.team_id
                 AND member.id = assignment.actor_member_id
                WHERE assignment.organization_id = ? AND assignment.id = ?
                """,
                (result, ignored) -> authority(result),
                event.organizationId(), assignmentId),
                "ResponsibilityAssignment", assignmentId);
        requireTeam(event, row.teamId());
        InboxItemType itemType = switch (row.kind()) {
            case "OWNER" -> InboxItemType.OWNERSHIP;
            case "EXECUTOR" -> InboxItemType.EXECUTION;
            default -> null;
        };
        if (itemType == null) {
            return InboxMutation.ignore();
        }
        Optional<Terminal> terminal = forcedReason.map(reason -> new Terminal(reason, forcedAt));
        if (terminal.isEmpty() && !replay && row.status().equals("RELEASED")) {
            terminal = Optional.of(new Terminal(
                    InboxCloseReason.RESPONSIBILITY_RELEASED,
                    row.terminalAt().orElseThrow()));
        }
        return mutation(event, row, itemType, InboxSourceType.RESPONSIBILITY_ASSIGNMENT,
                assignmentId, InboxSourceRevision.INITIAL, InboxPriority.NORMAL,
                Optional.empty(), terminal);
    }

    private InboxMutation review(
            ProjectionEvent event,
            JsonNode payload,
            boolean replay,
            Optional<InboxCloseReason> forcedReason) {
        UUID requestId = uuid(payload, "reviewRequestId", true).orElseThrow();
        AuthorityRow row = one(jdbc.query(
                """
                SELECT request.team_id, context.reviewer_owner_member_id AS member_id,
                       request.status AS kind, request.status,
                       request.created_at AS opened_at, request.updated_at AS terminal_at,
                       member.status AS member_status, member.updated_at AS member_updated_at,
                       request.revision AS source_revision
                FROM crewscope.review_request request
                JOIN crewscope.review_context_package context
                  ON context.id = request.context_package_id
                LEFT JOIN crewscope.team_member member
                  ON member.organization_id = request.organization_id
                 AND member.team_id = request.team_id
                 AND member.id = context.reviewer_owner_member_id
                WHERE request.organization_id = ? AND request.id = ?
                """,
                (result, ignored) -> authority(result),
                event.organizationId(), requestId),
                "ReviewRequest", requestId);
        if (row.memberId().isEmpty()) {
            return InboxMutation.ignore();
        }
        requireTeam(event, row.teamId());
        Optional<Terminal> terminal = forcedReason.map(reason ->
                new Terminal(reason, event.occurredAt()));
        if (terminal.isEmpty() && !replay) {
            terminal = switch (row.status()) {
                case "COMPLETED" -> Optional.of(new Terminal(
                        InboxCloseReason.REVIEW_COMPLETED, row.terminalAt().orElseThrow()));
                case "INVALIDATED" -> Optional.of(new Terminal(
                        InboxCloseReason.REVIEW_SUPERSEDED, row.terminalAt().orElseThrow()));
                default -> Optional.empty();
            };
        }
        return mutation(event, row, InboxItemType.REVIEW, InboxSourceType.REVIEW_REQUEST,
                requestId, new InboxSourceRevision(row.sourceRevision()), InboxPriority.HIGH,
                Optional.empty(), terminal);
    }

    private InboxMutation confirmation(
            ProjectionEvent event,
            JsonNode payload,
            boolean replay,
            Optional<InboxCloseReason> forcedReason) {
        UUID bundleId = uuid(payload, "actionBundleId", true).orElseThrow();
        AuthorityRow row = one(jdbc.query(
                """
                SELECT bundle.team_id, assignment.actor_member_id AS member_id,
                       COALESCE(confirmation.status, 'PLANNED') AS kind,
                       COALESCE(confirmation.status, 'PLANNED') AS status,
                       bundle.created_at AS opened_at,
                       confirmation.updated_at AS terminal_at,
                       member.status AS member_status, member.updated_at AS member_updated_at,
                       bundle.version AS source_revision, bundle.valid_until AS deadline
                FROM crewscope.action_bundle bundle
                JOIN crewscope.responsibility_assignment assignment
                  ON assignment.id = bundle.responsibility_assignment_id
                JOIN crewscope.team_member member
                  ON member.organization_id = bundle.organization_id
                 AND member.team_id = bundle.team_id
                 AND member.id = assignment.actor_member_id
                LEFT JOIN crewscope.action_confirmation confirmation
                  ON confirmation.action_bundle_id = bundle.id
                WHERE bundle.organization_id = ? AND bundle.id = ?
                """,
                (result, ignored) -> authority(result),
                event.organizationId(), bundleId),
                "ActionBundle", bundleId);
        requireTeam(event, row.teamId());
        Optional<Terminal> terminal = forcedReason.map(reason ->
                new Terminal(reason, event.occurredAt()));
        if (terminal.isEmpty() && !replay && !row.status().equals("PLANNED")) {
            InboxCloseReason reason = row.status().equals("CANCELLED")
                    ? InboxCloseReason.CONFIRMATION_CANCELLED
                    : InboxCloseReason.CONFIRMATION_COMPLETED;
            terminal = Optional.of(new Terminal(reason, row.terminalAt().orElseThrow()));
        }
        return mutation(event, row, InboxItemType.CONFIRMATION,
                InboxSourceType.ACTION_CONFIRMATION, bundleId,
                new InboxSourceRevision(row.sourceRevision()), InboxPriority.URGENT,
                row.deadline(), terminal);
    }

    private InboxMutation taskException(
            ProjectionEvent event,
            JsonNode payload,
            boolean replay,
            Optional<InboxCloseReason> forcedReason) {
        String idField = forcedReason.isPresent() ? "targetExecutionId" : "taskExecutionId";
        UUID executionId = uuid(payload, idField, true).orElseThrow();
        AuthorityRow row = one(jdbc.query(
                """
                SELECT execution.team_id, owner.actor_member_id AS member_id,
                       execution.status AS kind, execution.status,
                       COALESCE(execution.terminal_decided_at, execution.updated_at) AS opened_at,
                       task.updated_at AS terminal_at,
                       member.status AS member_status, member.updated_at AS member_updated_at,
                       execution.attempt AS source_revision,
                       (task.current_execution_id = execution.id) AS is_current
                FROM crewscope.task_execution execution
                JOIN crewscope.task task ON task.id = execution.task_id
                JOIN LATERAL (
                    SELECT assignment.actor_member_id
                    FROM crewscope.responsibility_assignment assignment
                    WHERE assignment.organization_id = task.organization_id
                      AND assignment.team_id = task.team_id
                      AND assignment.work_item_id = task.work_item_id
                      AND assignment.role = 'OWNER' AND assignment.status = 'ACTIVE'
                    ORDER BY assignment.accepted_at DESC, assignment.id DESC LIMIT 1
                ) owner ON TRUE
                JOIN crewscope.team_member member
                  ON member.organization_id = execution.organization_id
                 AND member.team_id = execution.team_id
                 AND member.id = owner.actor_member_id
                WHERE execution.organization_id = ? AND execution.id = ?
                """,
                (result, ignored) -> authority(result),
                event.organizationId(), executionId),
                "TaskExecution", executionId);
        requireTeam(event, row.teamId());
        Optional<Terminal> terminal = forcedReason.map(reason ->
                new Terminal(reason, event.occurredAt()));
        if (terminal.isEmpty() && !replay && !row.current()) {
            terminal = Optional.of(new Terminal(
                    InboxCloseReason.EXCEPTION_RECOVERED,
                    row.terminalAt().orElse(event.occurredAt())));
        }
        return mutation(event, row, InboxItemType.EXCEPTION, InboxSourceType.TASK_EXECUTION,
                executionId, new InboxSourceRevision(row.sourceRevision()), InboxPriority.URGENT,
                Optional.empty(), terminal);
    }

    private List<InboxMutation> actionDelivery(
            ProjectionEvent event, JsonNode payload, boolean replay) {
        UUID actionId = uuid(payload, "plannedActionId", true).orElseThrow();
        String eventStatus = event.eventType().equals("ACTION_RECEIPT_RECORDED")
                ? scalar(payload, "result", true).orElseThrow()
                : scalar(payload, "status", true).orElseThrow();
        if (!ACTION_EXCEPTION_OPEN.contains(eventStatus)
                && !ACTION_EXCEPTION_RESOLVED.contains(eventStatus)) {
            return List.of();
        }
        AuthorityRow row = one(jdbc.query(
                """
                SELECT bundle.team_id, assignment.actor_member_id AS member_id,
                       dispatch.status AS kind, dispatch.status,
                       dispatch.created_at AS opened_at, dispatch.updated_at AS terminal_at,
                       member.status AS member_status, member.updated_at AS member_updated_at,
                       0::BIGINT AS source_revision
                FROM crewscope.planned_action action
                JOIN crewscope.action_bundle bundle ON bundle.id = action.action_bundle_id
                JOIN crewscope.responsibility_assignment assignment
                  ON assignment.id = bundle.responsibility_assignment_id
                JOIN crewscope.team_member member
                  ON member.organization_id = bundle.organization_id
                 AND member.team_id = bundle.team_id
                 AND member.id = assignment.actor_member_id
                JOIN crewscope.action_dispatch dispatch ON dispatch.action_id = action.id
                WHERE bundle.organization_id = ? AND action.id = ?
                """,
                (result, ignored) -> authority(result),
                event.organizationId(), actionId),
                "PlannedAction", actionId);
        requireTeam(event, row.teamId());
        Optional<Terminal> terminal = ACTION_EXCEPTION_RESOLVED.contains(eventStatus)
                ? Optional.of(new Terminal(
                        InboxCloseReason.EXCEPTION_RESOLVED, event.occurredAt()))
                : Optional.empty();
        if (terminal.isEmpty() && !replay && ACTION_EXCEPTION_RESOLVED.contains(row.status())) {
            terminal = Optional.of(new Terminal(
                    InboxCloseReason.EXCEPTION_RECOVERED,
                    row.terminalAt().orElse(event.occurredAt())));
        }
        AuthorityRow opened = row.withOpenedAt(event.occurredAt());
        InboxMutation mutation = mutation(event, opened, InboxItemType.EXCEPTION,
                InboxSourceType.ACTION_DELIVERY, actionId, InboxSourceRevision.INITIAL,
                InboxPriority.URGENT, Optional.empty(), terminal);
        return List.of(ACTION_EXCEPTION_RESOLVED.contains(eventStatus)
                ? mutation.closeOnly()
                : mutation);
    }

    private InboxMutation mutation(
            ProjectionEvent event,
            AuthorityRow row,
            InboxItemType itemType,
            InboxSourceType sourceType,
            UUID sourceId,
            InboxSourceRevision revision,
            InboxPriority priority,
            Optional<UtcTimestamp> deadline,
            Optional<Terminal> terminal) {
        TeamMemberId memberId = new TeamMemberId(row.memberId().orElseThrow());
        InboxSourceKey key = new InboxSourceKey(
                new OrganizationId(event.organizationId()), memberId, itemType,
                sourceType, sourceId, revision);
        InboxSource source = InboxSource.open(key, priority, deadline, row.openedAt());
        Optional<Terminal> resolved = terminal;
        if (resolved.isEmpty() && !row.memberStatus().equals("ACTIVE")) {
            resolved = Optional.of(new Terminal(
                    InboxCloseReason.MEMBER_NO_LONGER_ELIGIBLE,
                    max(row.openedAt(), row.memberUpdatedAt())));
        }
        if (resolved.isPresent()) {
            Terminal end = resolved.orElseThrow();
            source = source.close(end.reason(), max(row.openedAt(), end.occurredAt()));
        }
        return InboxMutation.upsert(new TeamId(row.teamId()), source);
    }

    private void apply(ProjectionGenerationLease lease, InboxMutation mutation) {
        if (mutation.source().isEmpty()) {
            return;
        }
        InboxItem item = InboxItem.project(
                mutation.teamId().orElseThrow(), lease.key().projectionName(),
                lease.key().generation(), DEFINITION.projectionSchemaVersion(),
                mutation.source().orElseThrow());
        InboxSource source = item.source();
        if (mutation.insertIfMissing()) {
            jdbc.update(
                    """
                INSERT INTO crewscope.inbox_item (
                    organization_id, team_id, member_id, projection_name, generation,
                    inbox_item_id, projection_schema_version, item_type, source_type,
                    source_id, source_revision, priority, deadline, opened_at,
                    source_status, close_reason, closed_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (organization_id, projection_name, generation, inbox_item_id)
                DO NOTHING
                """,
                    item.organizationId().value(), item.teamId().value(), item.memberId().value(),
                    item.projectionName().value(), item.projectionGeneration().value(), item.id().value(),
                    item.projectionSchemaVersion().value(), source.key().itemType().name(),
                    source.key().sourceType().name(), source.key().sourceId(),
                    source.key().sourceRevision().value(), source.priority().name(),
                    source.deadline().map(UtcTimestamp::toOffsetDateTime).orElse(null),
                    source.openedAt().toOffsetDateTime(), source.status().name(),
                    source.closeReason().map(Enum::name).orElse(null),
                    source.closedAt().map(UtcTimestamp::toOffsetDateTime).orElse(null));
        }
        if (!source.isOpen()) {
            closeExisting(item, source.closeReason().orElseThrow(), source.closedAt().orElseThrow());
        }
    }

    private void closeExisting(InboxItem item, InboxCloseReason reason, UtcTimestamp closedAt) {
        jdbc.update(
                """
                UPDATE crewscope.inbox_item
                SET source_status = 'CLOSED', close_reason = ?, closed_at = ?
                WHERE organization_id = ? AND projection_name = ? AND generation = ?
                  AND inbox_item_id = ? AND source_status = 'OPEN'
                """,
                reason.name(), closedAt.toOffsetDateTime(), item.organizationId().value(),
                item.projectionName().value(), item.projectionGeneration().value(), item.id().value());
    }

    private int closeIneligible(ProjectionGenerationLease lease, TeamId teamId) {
        return jdbc.update(
                """
                UPDATE crewscope.inbox_item item
                SET source_status = 'CLOSED',
                    close_reason = 'MEMBER_NO_LONGER_ELIGIBLE',
                    closed_at = GREATEST(item.opened_at, member.updated_at)
                FROM crewscope.team_member member
                WHERE item.organization_id = ? AND item.team_id = ?
                  AND item.projection_name = ? AND item.generation = ?
                  AND item.source_status = 'OPEN'
                  AND member.organization_id = item.organization_id
                  AND member.team_id = item.team_id AND member.id = item.member_id
                  AND member.status <> 'ACTIVE'
                """,
                lease.key().organizationId().value(), teamId.value(),
                lease.key().projectionName().value(), lease.key().generation().value());
    }

    private void mergeExpected(Map<InboxItemId, InboxItem> expected, InboxMutation mutation) {
        if (mutation.source().isEmpty()) {
            return;
        }
        InboxItem candidate = InboxItem.project(
                mutation.teamId().orElseThrow(), PROJECTION_NAME,
                io.crewscope.domain.projection.ProjectionGeneration.FIRST,
                DEFINITION.projectionSchemaVersion(), mutation.source().orElseThrow());
        InboxItem current = expected.get(candidate.id());
        if (current == null) {
            if (mutation.insertIfMissing()) {
                expected.put(candidate.id(), candidate);
            }
        } else if (current.source().isOpen() && !candidate.source().isOpen()) {
            expected.put(candidate.id(), current.close(
                    candidate.source().closeReason().orElseThrow(),
                    candidate.source().closedAt().orElseThrow()));
        }
    }

    private void closeExpectedIneligible(
            OrganizationId organizationId, Map<InboxItemId, InboxItem> expected) {
        Map<UUID, MemberState> members = new LinkedHashMap<>();
        jdbc.query(
                """
                SELECT id, status, updated_at FROM crewscope.team_member
                WHERE organization_id = ? AND status <> 'ACTIVE'
                """,
                (RowCallbackHandler) row -> members.put(
                        row.getObject("id", UUID.class),
                        new MemberState(
                                row.getString("status"),
                                UtcTimestamp.from(row.getObject("updated_at", OffsetDateTime.class)))),
                organizationId.value());
        expected.replaceAll((id, item) -> {
            MemberState member = members.get(item.memberId().value());
            if (!item.source().isOpen() || member == null) {
                return item;
            }
            return item.close(
                    InboxCloseReason.MEMBER_NO_LONGER_ELIGIBLE,
                    max(item.source().openedAt(), member.updatedAt()));
        });
    }

    private AuthorityRow authority(ResultSet row) throws SQLException {
        Long revision = nullableLong(row, "source_revision").orElse(0L);
        Boolean current = nullableBoolean(row, "is_current").orElse(true);
        return new AuthorityRow(
                row.getObject("team_id", UUID.class),
                Optional.ofNullable(row.getObject("member_id", UUID.class)),
                row.getString("kind"), row.getString("status"),
                UtcTimestamp.from(row.getObject("opened_at", OffsetDateTime.class)),
                Optional.ofNullable(row.getObject("terminal_at", OffsetDateTime.class))
                        .map(UtcTimestamp::from),
                Optional.ofNullable(row.getString("member_status")).orElse("INELIGIBLE"),
                Optional.ofNullable(row.getObject("member_updated_at", OffsetDateTime.class))
                        .map(UtcTimestamp::from)
                        .orElse(UtcTimestamp.from(row.getObject("opened_at", OffsetDateTime.class))),
                revision,
                nullableTimestamp(row, "deadline"),
                current);
    }

    private ProjectionEvent historyEvent(ResultSet row) throws SQLException {
        return new ProjectionEvent(
                row.getObject("event_id", UUID.class), row.getString("event_type"),
                row.getString("schema_version"), row.getObject("organization_id", UUID.class),
                Optional.ofNullable(row.getObject("team_id", UUID.class)),
                Optional.ofNullable(row.getObject("workspace_id", UUID.class)),
                row.getString("subject_type"), row.getObject("subject_id", UUID.class),
                row.getLong("aggregate_version"),
                io.crewscope.domain.shared.event.EventActorType.valueOf(row.getString("actor_type")),
                Optional.ofNullable(row.getObject("actor_id", UUID.class)),
                row.getObject("correlation_id", UUID.class),
                Optional.ofNullable(row.getObject("causation_id", UUID.class)),
                UtcTimestamp.from(row.getObject("occurred_at", OffsetDateTime.class)),
                row.getString("payload"));
    }

    private InboxItem readItem(ResultSet row) throws SQLException {
        InboxSourceKey key = new InboxSourceKey(
                new OrganizationId(row.getObject("organization_id", UUID.class)),
                new TeamMemberId(row.getObject("member_id", UUID.class)),
                InboxItemType.valueOf(row.getString("item_type")),
                InboxSourceType.valueOf(row.getString("source_type")),
                row.getObject("source_id", UUID.class),
                new InboxSourceRevision(row.getLong("source_revision")));
        InboxSource source = InboxSource.open(
                key, InboxPriority.valueOf(row.getString("priority")),
                Optional.ofNullable(row.getObject("deadline", OffsetDateTime.class))
                        .map(UtcTimestamp::from),
                UtcTimestamp.from(row.getObject("opened_at", OffsetDateTime.class)));
        if (row.getString("source_status").equals("CLOSED")) {
            source = source.close(
                    InboxCloseReason.valueOf(row.getString("close_reason")),
                    UtcTimestamp.from(row.getObject("closed_at", OffsetDateTime.class)));
        }
        return new InboxItem(
                new InboxItemId(row.getObject("inbox_item_id", UUID.class)),
                new TeamId(row.getObject("team_id", UUID.class)),
                new ProjectionName(row.getString("projection_name")),
                new io.crewscope.domain.projection.ProjectionGeneration(row.getLong("generation")),
                new SchemaVersion(row.getInt("projection_schema_version")), source);
    }

    private String canonical(InboxItem item) {
        InboxSource source = item.source();
        return canonical(List.of(
                item.id().toString(), item.organizationId().toString(), item.teamId().toString(),
                item.memberId().toString(),
                Integer.toString(item.projectionSchemaVersion().value()),
                source.key().itemType().name(), source.key().sourceType().name(),
                source.key().sourceId().toString(),
                Long.toString(source.key().sourceRevision().value()), source.priority().name(),
                source.deadline().map(Object::toString).orElse(""), source.openedAt().toString(),
                source.status().name(), source.closeReason().map(Enum::name).orElse(""),
                source.closedAt().map(Object::toString).orElse("")));
    }

    private JsonNode payload(String json) {
        try {
            JsonNode value = objectMapper.readTree(json);
            if (value == null || !value.isObject()) {
                throw new InvalidProjectionEventException(
                        "Registered Inbox payload must be a JSON object");
            }
            return value;
        } catch (InvalidProjectionEventException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new InvalidProjectionEventException(
                    "Registered Inbox payload is not valid JSON", exception);
        }
    }

    private Optional<String> scalar(JsonNode root, String field, boolean required) {
        JsonNode value = root.get(field);
        while (value != null && value.isObject() && value.size() == 1 && value.get("value") != null) {
            value = value.get("value");
        }
        if (value == null || value.isNull()) {
            if (required) {
                throw new InvalidProjectionEventException(
                        "Registered Inbox payload is missing reviewed field " + field);
            }
            return Optional.empty();
        }
        if (value.isString()) {
            return Optional.of(value.stringValue());
        }
        if (value.isIntegralNumber() || value.isBoolean()) {
            return Optional.of(value.toString());
        }
        throw new InvalidProjectionEventException(
                "Registered Inbox payload field must be scalar: " + field);
    }

    private Optional<UUID> uuid(JsonNode root, String field, boolean required) {
        Optional<String> value = scalar(root, field, required);
        if (value.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(value.orElseThrow()));
        } catch (IllegalArgumentException exception) {
            throw new InvalidProjectionEventException(
                    "Registered Inbox identity must be a UUID: " + field, exception);
        }
    }

    private void requireTeam(ProjectionEvent event, UUID authorityTeamId) {
        if (event.teamId().filter(authorityTeamId::equals).isEmpty()) {
            throw new InvalidProjectionEventException(
                    "Inbox event and current authority Team do not match");
        }
    }

    private static <T> T one(List<T> values, String type, UUID id) {
        if (values.size() != 1) {
            throw new InvalidProjectionEventException(
                    "Registered Inbox event cannot resolve exact " + type + " " + id);
        }
        return values.get(0);
    }

    private static Optional<Long> nullableLong(ResultSet row, String column) {
        try {
            long value = row.getLong(column);
            return row.wasNull() ? Optional.empty() : Optional.of(value);
        } catch (SQLException exception) {
            return Optional.empty();
        }
    }

    private static Optional<Boolean> nullableBoolean(ResultSet row, String column) {
        try {
            boolean value = row.getBoolean(column);
            return row.wasNull() ? Optional.empty() : Optional.of(value);
        } catch (SQLException exception) {
            return Optional.empty();
        }
    }

    private static Optional<UtcTimestamp> nullableTimestamp(ResultSet row, String column) {
        try {
            return Optional.ofNullable(row.getObject(column, OffsetDateTime.class))
                    .map(UtcTimestamp::from);
        } catch (SQLException exception) {
            return Optional.empty();
        }
    }

    private static ProjectionSnapshot snapshot(List<String> canonicalRows) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            canonicalRows.stream().sorted(Comparator.naturalOrder()).forEach(row -> {
                byte[] bytes = row.getBytes(StandardCharsets.UTF_8);
                digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) ':');
                digest.update(bytes);
            });
            return new ProjectionSnapshot(
                    canonicalRows.size(),
                    new ProjectionCanonicalHash(HexFormat.of().formatHex(digest.digest())),
                    0,
                    List.of());
        } catch (Exception exception) {
            throw new IllegalStateException("Inbox canonical SHA-256 is unavailable", exception);
        }
    }

    private static String canonical(List<String> values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            result.append(value.length()).append(':').append(value);
        }
        return result.toString();
    }

    private static UtcTimestamp max(UtcTimestamp left, UtcTimestamp right) {
        return left.compareTo(right) >= 0 ? left : right;
    }

    private record Terminal(InboxCloseReason reason, UtcTimestamp occurredAt) {}

    private record MemberState(String status, UtcTimestamp updatedAt) {}

    private record InboxMutation(
            Optional<TeamId> teamId,
            Optional<InboxSource> source,
            boolean insertIfMissing) {
        static InboxMutation ignore() {
            return new InboxMutation(Optional.empty(), Optional.empty(), false);
        }

        static InboxMutation upsert(TeamId teamId, InboxSource source) {
            return new InboxMutation(Optional.of(teamId), Optional.of(source), true);
        }

        InboxMutation closeOnly() {
            return new InboxMutation(teamId, source, false);
        }
    }

    private record AuthorityRow(
            UUID teamId,
            Optional<UUID> memberId,
            String kind,
            String status,
            UtcTimestamp openedAt,
            Optional<UtcTimestamp> terminalAt,
            String memberStatus,
            UtcTimestamp memberUpdatedAt,
            long sourceRevision,
            Optional<UtcTimestamp> deadline,
            boolean current) {

        AuthorityRow withOpenedAt(UtcTimestamp value) {
            return new AuthorityRow(
                    teamId, memberId, kind, status, value, terminalAt, memberStatus,
                    memberUpdatedAt, sourceRevision, deadline, current);
        }
    }
}
