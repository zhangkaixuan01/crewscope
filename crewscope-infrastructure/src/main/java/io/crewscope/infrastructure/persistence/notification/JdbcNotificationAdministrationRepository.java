package io.crewscope.infrastructure.persistence.notification;

import io.crewscope.application.notification.NotificationAdministrationRepository;
import io.crewscope.application.notification.NotificationDeliveryCursor;
import io.crewscope.application.notification.NotificationDeliveryFilter;
import io.crewscope.application.notification.NotificationDeliveryPage;
import io.crewscope.application.notification.NotificationDeliveryView;
import io.crewscope.application.notification.NotificationTemplateView;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.inbox.InboxItemType;
import io.crewscope.domain.notification.NotificationDeliveryId;
import io.crewscope.domain.notification.NotificationDeliveryStatus;
import io.crewscope.domain.notification.NotificationFailureCode;
import io.crewscope.domain.notification.NotificationPreference;
import io.crewscope.domain.notification.NotificationTemplateId;
import io.crewscope.domain.notification.NotificationTemplateRef;
import io.crewscope.domain.notification.NotificationTemplateStatus;
import io.crewscope.domain.notification.NotificationTemplateVersion;
import io.crewscope.domain.notification.NotificationVariableType;
import io.crewscope.domain.provider.ProviderBindingId;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamMemberId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Team-scoped notification administration queries and optimistic preference writes. */
@Repository
public class JdbcNotificationAdministrationRepository
        implements NotificationAdministrationRepository {

    private static final String DELIVERY_SELECT = """
            SELECT action.organization_id, action.team_id, action.recipient_member_id,
                   action.template_id, action.template_version, action.provider_binding_id,
                   intent.item_type, delivery.delivery_id, delivery.status,
                   delivery.attempt_count, delivery.redelivery_of, delivery.version,
                   delivery.created_at, delivery.updated_at,
                   receipt.failure_code, receipt.evidence_code
            FROM crewscope.notification_delivery delivery
            JOIN crewscope.notification_planned_action action
              ON action.organization_id = delivery.organization_id
             AND action.action_id = delivery.action_id
            JOIN crewscope.notification_intent intent
              ON intent.organization_id = action.organization_id
             AND intent.team_id = action.team_id
             AND intent.recipient_member_id = action.recipient_member_id
             AND intent.projection_name = action.projection_name
             AND intent.generation = action.generation
             AND intent.intent_id = action.intent_id
            LEFT JOIN crewscope.notification_receipt receipt
              ON receipt.organization_id = delivery.organization_id
             AND receipt.receipt_id = delivery.receipt_id
            WHERE action.organization_id = ? AND action.team_id = ?
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcNotificationAdministrationRepository(
            JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<NotificationPreference> findPreference(
            OrganizationId organizationId, TeamId teamId, TeamMemberId memberId) {
        return one(jdbc.query(
                """
                SELECT enabled, enabled_item_types::TEXT AS enabled_item_types,
                       muted_until, version
                FROM crewscope.notification_preference
                WHERE organization_id = ? AND team_id = ? AND member_id = ?
                """,
                (row, ignored) -> new NotificationPreference(
                        memberId,
                        row.getBoolean("enabled"),
                        itemTypes(row.getString("enabled_item_types")),
                        Optional.ofNullable(row.getObject("muted_until", OffsetDateTime.class))
                                .map(UtcTimestamp::from),
                        row.getLong("version")),
                organizationId.value(), teamId.value(), memberId.value()));
    }

    @Override
    @Transactional
    public NotificationPreference savePreference(
            OrganizationId organizationId,
            TeamId teamId,
            NotificationPreference preference,
            long expectedVersion,
            Principal actor,
            UtcTimestamp now) {
        NotificationPreference value = Objects.requireNonNull(preference, "preference");
        Principal principal = Objects.requireNonNull(actor, "actor");
        if (!principal.scope().organizationId().equals(organizationId)
                || value.version() != expectedVersion + 1) {
            throw new IllegalArgumentException("Notification preference scope or version is invalid");
        }
        String itemTypes = objectMapper.writeValueAsString(
                value.enabledItemTypes().stream().map(Enum::name).sorted().toList());
        int updated;
        if (expectedVersion == 0) {
            updated = jdbc.update(
                    """
                    INSERT INTO crewscope.notification_preference (
                        organization_id, team_id, member_id, enabled, enabled_item_types,
                        muted_until, version, created_at, created_by_principal_id,
                        updated_at, updated_by_principal_id
                    ) VALUES (?, ?, ?, ?, CAST(? AS JSONB), ?, 1, ?, ?, ?, ?)
                    ON CONFLICT (organization_id, team_id, member_id) DO NOTHING
                    """,
                    organizationId.value(), teamId.value(), value.memberId().value(),
                    value.enabled(), itemTypes,
                    value.mutedUntil().map(UtcTimestamp::toOffsetDateTime).orElse(null),
                    now.toOffsetDateTime(), principal.id().value(),
                    now.toOffsetDateTime(), principal.id().value());
        } else {
            updated = jdbc.update(
                    """
                    UPDATE crewscope.notification_preference
                    SET enabled = ?, enabled_item_types = CAST(? AS JSONB), muted_until = ?,
                        version = ?, updated_at = ?, updated_by_principal_id = ?
                    WHERE organization_id = ? AND team_id = ? AND member_id = ? AND version = ?
                    """,
                    value.enabled(), itemTypes,
                    value.mutedUntil().map(UtcTimestamp::toOffsetDateTime).orElse(null),
                    value.version(), now.toOffsetDateTime(), principal.id().value(),
                    organizationId.value(), teamId.value(), value.memberId().value(),
                    expectedVersion);
        }
        if (updated != 1) {
            long actual = findPreference(organizationId, teamId, value.memberId())
                    .map(NotificationPreference::version)
                    .orElse(0L);
            throw new OptimisticLockConflictException(
                    "NotificationPreference", value.memberId(), expectedVersion, actual);
        }
        return value;
    }

    @Override
    @Transactional(readOnly = true)
    public List<NotificationTemplateView> listTemplates() {
        List<TemplateRow> rows = jdbc.query(
                """
                SELECT template.template_id, template.template_version,
                       template.server_template_key, template.status,
                       variable.variable_name, variable.variable_type,
                       variable.maximum_length
                FROM crewscope.notification_template template
                LEFT JOIN crewscope.notification_template_variable variable
                  ON variable.template_id = template.template_id
                 AND variable.template_version = template.template_version
                ORDER BY template.server_template_key, template.template_version DESC,
                         variable.variable_name
                """,
                (row, ignored) -> new TemplateRow(
                        row.getObject("template_id", UUID.class),
                        row.getLong("template_version"),
                        row.getString("server_template_key"),
                        row.getString("status"),
                        row.getString("variable_name"),
                        row.getString("variable_type"),
                        row.getObject("maximum_length", Integer.class)));
        Map<String, TemplateAccumulator> grouped = new LinkedHashMap<>();
        for (TemplateRow row : rows) {
            String key = row.id() + ":" + row.version();
            TemplateAccumulator template = grouped.computeIfAbsent(
                    key,
                    ignored -> new TemplateAccumulator(
                            row.id(), row.version(), row.templateKey(), row.status()));
            if (row.variableName() != null) {
                template.variables().add(new NotificationTemplateView.VariableView(
                        row.variableName(),
                        NotificationVariableType.valueOf(row.variableType()),
                        row.maximumLength()));
            }
        }
        return grouped.values().stream().map(TemplateAccumulator::view).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public NotificationDeliveryPage findDeliveries(
            OrganizationId organizationId,
            TeamId teamId,
            NotificationDeliveryFilter filter,
            Optional<NotificationDeliveryCursor> after,
            int limit) {
        StringBuilder sql = new StringBuilder(DELIVERY_SELECT);
        List<Object> parameters = new ArrayList<>(List.of(
                organizationId.value(), teamId.value()));
        appendIn(sql, parameters, "delivery.status", filter.statuses().stream()
                .map(Enum::name).sorted().toList());
        appendIn(sql, parameters, "intent.item_type", filter.itemTypes().stream()
                .map(Enum::name).sorted().toList());
        filter.recipientMemberId().ifPresent(memberId -> {
            sql.append(" AND action.recipient_member_id = ?");
            parameters.add(memberId.value());
        });
        after.ifPresent(cursor -> {
            sql.append(" AND (delivery.updated_at, delivery.delivery_id) < (?, ?)");
            parameters.add(cursor.updatedAt().toOffsetDateTime());
            parameters.add(cursor.deliveryId().value());
        });
        sql.append(" ORDER BY delivery.updated_at DESC, delivery.delivery_id DESC LIMIT ?");
        parameters.add(limit + 1);
        List<NotificationDeliveryView> rows = jdbc.query(
                sql.toString(), this::delivery, parameters.toArray());
        boolean hasMore = rows.size() > limit;
        List<NotificationDeliveryView> items = hasMore ? rows.subList(0, limit) : rows;
        return new NotificationDeliveryPage(
                items,
                hasMore ? Optional.of(items.get(items.size() - 1).cursor()) : Optional.empty());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<NotificationDeliveryView> findDelivery(
            OrganizationId organizationId,
            TeamId teamId,
            NotificationDeliveryId deliveryId) {
        return one(jdbc.query(
                DELIVERY_SELECT + " AND delivery.delivery_id = ?",
                this::delivery,
                organizationId.value(), teamId.value(), deliveryId.value()));
    }

    private NotificationDeliveryView delivery(ResultSet row, int ignored) throws SQLException {
        return new NotificationDeliveryView(
                new OrganizationId(row.getObject("organization_id", UUID.class)),
                new TeamId(row.getObject("team_id", UUID.class)),
                new NotificationDeliveryId(row.getObject("delivery_id", UUID.class)),
                new TeamMemberId(row.getObject("recipient_member_id", UUID.class)),
                InboxItemType.valueOf(row.getString("item_type")),
                new NotificationTemplateRef(
                        new NotificationTemplateId(row.getObject("template_id", UUID.class)),
                        new NotificationTemplateVersion(row.getLong("template_version"))),
                new ProviderBindingId(row.getObject("provider_binding_id", UUID.class)),
                NotificationDeliveryStatus.valueOf(row.getString("status")),
                row.getInt("attempt_count"),
                Optional.ofNullable(row.getString("failure_code"))
                        .map(NotificationFailureCode::valueOf),
                Optional.ofNullable(row.getString("evidence_code")),
                Optional.ofNullable(row.getObject("redelivery_of", UUID.class))
                        .map(NotificationDeliveryId::new),
                UtcTimestamp.from(row.getObject("created_at", OffsetDateTime.class)),
                UtcTimestamp.from(row.getObject("updated_at", OffsetDateTime.class)),
                row.getLong("version"));
    }

    private EnumSet<InboxItemType> itemTypes(String json) {
        JsonNode node = objectMapper.readTree(json);
        if (!node.isArray()) {
            throw new IllegalStateException("Stored notification preference item types are invalid");
        }
        EnumSet<InboxItemType> result = EnumSet.noneOf(InboxItemType.class);
        for (JsonNode value : node) {
            if (!value.isString() || !result.add(InboxItemType.valueOf(value.stringValue()))) {
                throw new IllegalStateException("Stored notification preference item types are invalid");
            }
        }
        return result;
    }

    private static void appendIn(
            StringBuilder sql, List<Object> parameters, String column, List<String> values) {
        if (values.isEmpty()) {
            return;
        }
        sql.append(" AND ").append(column).append(" IN (");
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                sql.append(',');
            }
            sql.append('?');
        }
        sql.append(')');
        parameters.addAll(values);
    }

    private static <T> Optional<T> one(List<T> values) {
        if (values.size() > 1) {
            throw new IllegalStateException("Expected at most one notification administration row");
        }
        return values.stream().findFirst();
    }

    private record TemplateRow(
            UUID id,
            long version,
            String templateKey,
            String status,
            String variableName,
            String variableType,
            Integer maximumLength) {}

    private record TemplateAccumulator(
            UUID id,
            long version,
            String templateKey,
            String status,
            List<NotificationTemplateView.VariableView> variables) {

        TemplateAccumulator(UUID id, long version, String templateKey, String status) {
            this(id, version, templateKey, status, new ArrayList<>());
        }

        NotificationTemplateView view() {
            return new NotificationTemplateView(
                    new NotificationTemplateRef(
                            new NotificationTemplateId(id),
                            new NotificationTemplateVersion(version)),
                    templateKey,
                    NotificationTemplateStatus.valueOf(status),
                    variables);
        }
    }
}
