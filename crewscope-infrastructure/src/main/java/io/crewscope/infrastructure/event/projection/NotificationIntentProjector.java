package io.crewscope.infrastructure.event.projection;

import io.crewscope.application.notification.NotificationIntentPolicy;
import io.crewscope.application.notification.NotificationIntentPolicyRegistry;
import io.crewscope.domain.inbox.InboxCloseReason;
import io.crewscope.domain.inbox.InboxItem;
import io.crewscope.domain.inbox.InboxItemId;
import io.crewscope.domain.inbox.InboxItemType;
import io.crewscope.domain.inbox.InboxPriority;
import io.crewscope.domain.inbox.InboxSource;
import io.crewscope.domain.inbox.InboxSourceKey;
import io.crewscope.domain.inbox.InboxSourceRevision;
import io.crewscope.domain.inbox.InboxSourceType;
import io.crewscope.domain.notification.NotificationAuthorizationFacts;
import io.crewscope.domain.notification.NotificationAuthorizationSnapshot;
import io.crewscope.domain.notification.NotificationDelivery;
import io.crewscope.domain.notification.NotificationIntent;
import io.crewscope.domain.notification.NotificationIntentId;
import io.crewscope.domain.notification.NotificationInvalidationReason;
import io.crewscope.domain.notification.NotificationPlannedAction;
import io.crewscope.domain.notification.NotificationPreference;
import io.crewscope.domain.notification.NotificationPreferenceDecision;
import io.crewscope.domain.notification.NotificationRecipientMappingId;
import io.crewscope.domain.notification.NotificationTemplate;
import io.crewscope.domain.notification.NotificationTemplateId;
import io.crewscope.domain.notification.NotificationTemplateRef;
import io.crewscope.domain.notification.NotificationTemplateStatus;
import io.crewscope.domain.notification.NotificationTemplateVersion;
import io.crewscope.domain.notification.NotificationVariableSpec;
import io.crewscope.domain.notification.TeamNotificationPolicyId;
import io.crewscope.domain.notification.TrustedNotificationOrigin;
import io.crewscope.domain.projection.ProjectionGenerationLease;
import io.crewscope.domain.provider.ConnectionGrantId;
import io.crewscope.domain.provider.ConnectionId;
import io.crewscope.domain.provider.ProviderBindingId;
import io.crewscope.domain.shared.event.SchemaVersion;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.TaskFactHash;
import io.crewscope.domain.team.TeamMemberId;
import java.net.URI;
import java.nio.charset.StandardCharsets;
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
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Projects fixed-template intents beside Inbox sources and creates policy-preauthorized delivery
 * plans only for the active member-inbox Generation. It never invokes a collaboration provider.
 */
@Component
public class NotificationIntentProjector {

    private static final String FIXED_TEMPLATE_CAPABILITY =
            "collaboration.notification.send-fixed-template";
    private static final Set<String> SAFE_VARIABLES = Set.of(
            "itemType", "sourceType", "sourceId", "sourceRevision", "priority", "deadline",
            "workItemTitle", "inboxUrl", "reviewUrl", "confirmationUrl", "taskUrl", "sourceUrl");

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final NotificationIntentPolicyRegistry policies;
    private final URI publicBaseUri;

    public NotificationIntentProjector(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            NotificationIntentPolicyRegistry policies,
            @Value("${crewscope.notification.public-base-uri:https://localhost}") String publicBaseUri) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.policies = Objects.requireNonNull(policies, "policies");
        this.publicBaseUri = requirePublicBaseUri(publicBaseUri);
    }

    /** Reconciles intents, plans and delivery-failure Inbox sources for one exact Team Generation. */
    public void reconcileTeam(
            ProjectionGenerationLease lease, TeamId teamId, UtcTimestamp now) {
        ProjectionGenerationLease target = Objects.requireNonNull(lease, "lease");
        TeamId team = Objects.requireNonNull(teamId, "teamId");
        UtcTimestamp timestamp = Objects.requireNonNull(now, "now");
        if (!target.key().projectionName().equals(InboxEventProjector.PROJECTION_NAME)) {
            throw new IllegalArgumentException("Notification intents must share member-inbox Generation");
        }
        reconcileDeliveryFailures(target, team, timestamp);
        List<ProjectedInbox> items = jdbc.query(
                """
                SELECT organization_id, team_id, member_id, projection_name, generation,
                       inbox_item_id, projection_schema_version, item_type, source_type,
                       source_id, source_revision, priority, deadline, opened_at,
                       source_status, close_reason, closed_at
                FROM crewscope.inbox_item
                WHERE organization_id = ? AND team_id = ? AND projection_name = ?
                  AND generation = ?
                ORDER BY opened_at, inbox_item_id
                """,
                (row, ignored) -> projectedInbox(row),
                target.key().organizationId().value(), team.value(),
                target.key().projectionName().value(), target.key().generation().value());
        boolean activeGeneration = isActiveGeneration(target);
        for (ProjectedInbox item : items) {
            reconcileItem(target, item, timestamp, activeGeneration);
        }
    }

    /** Called after Pointer switching so shadow intents can begin planning as the active view. */
    public void reconcileCurrentGeneration(
            OrganizationId organizationId, TeamId teamId, UtcTimestamp now) {
        ProjectionGenerationLease lease = one(jdbc.query(
                """
                SELECT generation.active_generation, state.fencing_token
                FROM crewscope.projection_pointer generation
                JOIN crewscope.projection_generation state
                  ON state.organization_id = generation.organization_id
                 AND state.projection_name = generation.projection_name
                 AND state.generation = generation.active_generation
                WHERE generation.organization_id = ? AND generation.projection_name = ?
                """,
                (row, ignored) -> new ProjectionGenerationLease(
                        new io.crewscope.domain.projection.ProjectionGenerationKey(
                                organizationId,
                                InboxEventProjector.PROJECTION_NAME,
                                new io.crewscope.domain.projection.ProjectionGeneration(
                                        row.getLong("active_generation"))),
                        new io.crewscope.domain.projection.ProjectionFencingToken(
                                row.getLong("fencing_token"))),
                Objects.requireNonNull(organizationId, "organizationId").value(),
                InboxEventProjector.PROJECTION_NAME.value()),
                "active member-inbox Generation");
        reconcileTeam(lease, teamId, now);
    }

    private void reconcileItem(
            ProjectionGenerationLease lease,
            ProjectedInbox projected,
            UtcTimestamp now,
            boolean activeGeneration) {
        InboxItem item = projected.item();
        Optional<NotificationIntentPolicy> selected = policies.find(
                item.source().key().itemType(), item.source().key().sourceType());
        if (selected.isEmpty()) {
            // NOTIFICATION_DELIVERY failures deliberately stop here to prevent projection loops.
            return;
        }
        NotificationIntentPolicy policy = selected.orElseThrow();
        if (!item.source().isOpen()) {
            if (activeGeneration) {
                invalidateLatest(item.organizationId(), NotificationIntentId.fromInboxItem(item.id()),
                        NotificationInvalidationReason.SOURCE, now);
            }
            return;
        }
        Optional<TemplateCoordinates> existingIntent = existingIntentTemplate(lease, item);
        Optional<NotificationTemplate> template = existingIntent.isPresent()
                ? template(existingIntent.orElseThrow(), policy.serverTemplateKey())
                : currentTemplate(policy.serverTemplateKey());
        if (template.isEmpty()) {
            if (activeGeneration) {
                invalidateLatest(item.organizationId(), NotificationIntentId.fromInboxItem(item.id()),
                        NotificationInvalidationReason.TEMPLATE, now);
            }
            return;
        }
        Optional<Map<String, String>> variables = variables(
                projected, template.orElseThrow());
        if (variables.isEmpty()) {
            if (activeGeneration) {
                invalidateLatest(item.organizationId(), NotificationIntentId.fromInboxItem(item.id()),
                        NotificationInvalidationReason.TEMPLATE, now);
            }
            return;
        }
        NotificationIntent intent = NotificationIntent.fromOpenInbox(
                item, template.orElseThrow(), variables.orElseThrow(), item.source().openedAt());
        boolean inserted = saveIntent(lease, item, intent);
        if (existingIntent.isEmpty() && !inserted) {
            // A concurrent projector pinned the immutable Intent first; reconcile against its
            // exact template coordinates instead of planning from a losing local candidate.
            reconcileItem(lease, projected, now, activeGeneration);
            return;
        }
        if (!activeGeneration) {
            return;
        }
        Optional<NotificationPreference> preference = preference(item);
        if (preference.isEmpty()
                || preference.orElseThrow().decide(item.source().key().itemType(), now)
                        == NotificationPreferenceDecision.DENIED) {
            invalidateLatest(item.organizationId(), intent.id(),
                    NotificationInvalidationReason.MEMBER_PREFERENCE, now);
            return;
        }
        AuthorizationResolution authorization = authorization(
                intent, preference.orElseThrow(), policy, now);
        if (authorization.facts().isEmpty()) {
            invalidateLatest(item.organizationId(), intent.id(), authorization.failureReason(), now);
            return;
        }
        plan(authorization.facts().orElseThrow(), policy, now);
    }

    private boolean saveIntent(
            ProjectionGenerationLease lease, InboxItem item, NotificationIntent intent) {
        return jdbc.update(
                """
                INSERT INTO crewscope.notification_intent (
                    organization_id, team_id, recipient_member_id, projection_name, generation,
                    intent_id, projection_schema_version, inbox_item_id, item_type, source_type,
                    source_id, source_revision, template_id, template_version,
                    variables, variable_hash, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::JSONB, ?, ?)
                ON CONFLICT (organization_id, projection_name, generation, inbox_item_id)
                DO NOTHING
                """,
                intent.organizationId().value(), intent.teamId().value(),
                intent.recipientMemberId().value(), lease.key().projectionName().value(),
                lease.key().generation().value(), intent.id().value(),
                intent.projectionSchemaVersion().value(), item.id().value(),
                intent.sourceKey().itemType().name(), intent.sourceKey().sourceType().name(),
                intent.sourceKey().sourceId(), intent.sourceKey().sourceRevision().value(),
                intent.template().templateId().value(), intent.template().version().value(),
                json(intent.variables().values()), intent.variables().hash().toString(),
                intent.createdAt().toOffsetDateTime()) == 1;
    }

    private void plan(
            NotificationAuthorizationFacts facts,
            NotificationIntentPolicy policy,
            UtcTimestamp now) {
        NotificationAuthorizationSnapshot snapshot =
                NotificationAuthorizationSnapshot.captureAutomatic(facts);
        Integer duplicate = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM crewscope.notification_planned_action
                WHERE organization_id = ? AND deduplication_key = ?
                """,
                Integer.class,
                facts.intent().organizationId().value(), snapshot.deduplicationKey().toString());
        if (duplicate != null && duplicate > 0) {
            return;
        }
        LatestPlan previous = latestPlan(facts.intent().organizationId(), facts.intent().id())
                .orElse(null);
        if (previous != null && !previous.authorizationDigest().equals(snapshot.digest().toString())
                && !previous.terminal()) {
            invalidate(previous, driftReason(previous, facts), now);
        }
        UtcTimestamp notBefore = facts.preference().decide(
                        facts.intent().sourceKey().itemType(), now)
                == NotificationPreferenceDecision.DEFERRED
                ? facts.preference().mutedUntil().orElseThrow()
                : now;
        NotificationPlannedAction action = NotificationPlannedAction.plan(
                facts,
                snapshot,
                notBefore,
                UtcTimestamp.from(notBefore.value().plus(policy.actionValidity())),
                Optional.empty());
        NotificationDelivery delivery = NotificationDelivery.ready(action, now);
        jdbc.update(
                """
                INSERT INTO crewscope.notification_planned_action (
                    organization_id, team_id, recipient_member_id, projection_name, generation,
                    action_id, intent_id, source_identity_hash, template_id, template_version,
                    variable_hash, recipient_mapping_id, recipient_mapping_version,
                    provider_binding_id, provider_binding_version, connection_id,
                    connection_version, connection_grant_id, connection_grant_version,
                    team_policy_id, team_policy_version, preference_version,
                    deduplication_key, authorization_digest, not_before, valid_until,
                    status, invalidation_reason, redelivery_of, action_digest,
                    version, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                          ?, ?, ?, ?, 'PLANNED', NULL, NULL, ?, 0, ?, ?)
                ON CONFLICT (organization_id, deduplication_key) DO NOTHING
                """,
                facts.intent().organizationId().value(), facts.intent().teamId().value(),
                facts.intent().recipientMemberId().value(), InboxEventProjector.PROJECTION_NAME.value(),
                facts.intent().projectionGeneration().value(), action.id().value(),
                facts.intent().id().value(),
                TaskFactHash.sha256(facts.intent().sourceKey().canonicalIdentity()).toString(),
                facts.intent().template().templateId().value(),
                facts.intent().template().version().value(), facts.intent().variables().hash().toString(),
                facts.recipientMappingId().value(), facts.recipientMappingVersion(),
                facts.providerBindingId().value(), facts.providerBindingVersion(),
                facts.connectionId().value(), facts.connectionVersion(), facts.grantId().value(),
                facts.grantVersion(), facts.teamPolicyId().value(), facts.teamPolicyVersion(),
                facts.preference().version(), snapshot.deduplicationKey().toString(),
                snapshot.digest().toString(), action.notBefore().toOffsetDateTime(),
                action.validUntil().toOffsetDateTime(), action.digest().toString(),
                now.toOffsetDateTime(), now.toOffsetDateTime());
        jdbc.update(
                """
                INSERT INTO crewscope.notification_delivery (
                    organization_id, delivery_id, action_id, action_digest, deduplication_key,
                    redelivery_of, status, attempt_count, next_attempt_at,
                    invalidation_reason, receipt_id, version, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, NULL, 'READY', 0, NULL, NULL, NULL, 0, ?, ?)
                ON CONFLICT (organization_id, deduplication_key) DO NOTHING
                """,
                facts.intent().organizationId().value(), delivery.id().value(), action.id().value(),
                action.digest().toString(), snapshot.deduplicationKey().toString(),
                now.toOffsetDateTime(), now.toOffsetDateTime());
    }

    private AuthorizationResolution authorization(
            NotificationIntent intent,
            NotificationPreference preference,
            NotificationIntentPolicy policy,
            UtcTimestamp now) {
        Optional<MappingRow> mappingResult = oneOptional(jdbc.query(
                """
                SELECT mapping.id, mapping.status, mapping.version,
                       mapping.provider_binding_id, mapping.provider_binding_version,
                       mapping.connection_id, mapping.connection_version,
                       mapping.connection_grant_id, mapping.connection_grant_version,
                       mapping.external_tenant_id, mapping.external_tenant_version,
                       mapping.provider_version, member.status AS member_status
                FROM crewscope.lark_member_mapping mapping
                JOIN crewscope.team_member member
                  ON member.organization_id = mapping.organization_id
                 AND member.team_id = mapping.team_id AND member.id = mapping.member_id
                WHERE mapping.organization_id = ? AND mapping.team_id = ?
                  AND mapping.member_id = ?
                ORDER BY mapping.version DESC, mapping.updated_at DESC, mapping.id DESC
                LIMIT 1
                """,
                (row, ignored) -> mapping(row),
                intent.organizationId().value(), intent.teamId().value(),
                intent.recipientMemberId().value()));
        if (mappingResult.isEmpty()
                || !mappingResult.orElseThrow().status().equals("ACTIVE")
                || !mappingResult.orElseThrow().memberStatus().equals("ACTIVE")) {
            return AuthorizationResolution.denied(NotificationInvalidationReason.RECIPIENT_MAPPING);
        }
        MappingRow mapping = mappingResult.orElseThrow();
        Optional<AuthorizationRow> currentResult = oneOptional(jdbc.query(
                """
                SELECT binding.status AS binding_status, binding.version AS binding_version,
                       binding.connection_id AS binding_connection_id,
                       binding.connection_version AS binding_connection_version,
                       binding.connection_grant_id AS binding_grant_id,
                       binding.connection_grant_version AS binding_grant_version,
                       binding.provider_type,
                       (binding.effective_capabilities @> ?::JSONB) AS binding_capable,
                       connection.status AS connection_status,
                       connection.version AS current_connection_version,
                       connection.expires_at AS connection_expires_at,
                       connection_grant.status AS grant_status,
                       connection_grant.version AS current_grant_version,
                       connection_grant.valid_from,
                       connection_grant.expires_at AS grant_expires_at,
                       (connection_grant.granted_capabilities @> ?::JSONB) AS grant_capable,
                       tenant.status AS tenant_status,
                       tenant.version AS current_tenant_version,
                       tenant.connection_id AS tenant_connection_id,
                       tenant.connection_version AS tenant_connection_version,
                       tenant.connection_grant_id AS tenant_grant_id,
                       tenant.connection_grant_version AS tenant_grant_version,
                       tenant.provider_version AS tenant_provider_version
                FROM crewscope.provider_binding binding
                JOIN crewscope.connection connection
                  ON connection.organization_id = binding.organization_id
                 AND connection.id = binding.connection_id
                JOIN crewscope.connection_grant connection_grant
                  ON connection_grant.organization_id = binding.organization_id
                 AND connection_grant.id = binding.connection_grant_id
                 AND connection_grant.connection_id = binding.connection_id
                JOIN crewscope.lark_external_tenant tenant
                  ON tenant.organization_id = binding.organization_id AND tenant.id = ?
                WHERE binding.organization_id = ? AND binding.team_id = ? AND binding.id = ?
                """,
                (row, ignored) -> authorization(row),
                capabilityJson(), capabilityJson(), mapping.externalTenantId(),
                intent.organizationId().value(), intent.teamId().value(),
                mapping.providerBindingId()));
        if (currentResult.isEmpty()) {
            return AuthorizationResolution.denied(NotificationInvalidationReason.PROVIDER_BINDING);
        }
        AuthorizationRow current = currentResult.orElseThrow();
        if (!current.bindingStatus().equals("ACTIVE")
                || current.bindingVersion() != mapping.providerBindingVersion()
                || !current.providerType().equals("COLLABORATION")
                || !current.bindingCapable()
                || !Objects.equals(current.bindingConnectionId(), mapping.connectionId())
                || !Objects.equals(current.bindingGrantId(), mapping.grantId())) {
            return AuthorizationResolution.denied(NotificationInvalidationReason.PROVIDER_BINDING);
        }
        if (!current.connectionStatus().equals("ACTIVE")
                || current.connectionVersion() != mapping.connectionVersion()
                || current.bindingConnectionVersion() != mapping.connectionVersion()
                || current.connectionExpiresAt().filter(value -> value.compareTo(now) <= 0).isPresent()) {
            return AuthorizationResolution.denied(NotificationInvalidationReason.CONNECTION);
        }
        if (!current.grantStatus().equals("ACTIVE")
                || current.grantVersion() != mapping.grantVersion()
                || current.bindingGrantVersion() != mapping.grantVersion()
                || !current.grantCapable()
                || current.validFrom().compareTo(now) > 0
                || current.grantExpiresAt().filter(value -> value.compareTo(now) <= 0).isPresent()) {
            return AuthorizationResolution.denied(NotificationInvalidationReason.GRANT);
        }
        if (!current.tenantStatus().equals("VERIFIED")
                || current.tenantVersion() != mapping.externalTenantVersion()
                || !current.tenantConnectionId().equals(mapping.connectionId())
                || current.tenantConnectionVersion() != mapping.connectionVersion()
                || !current.tenantGrantId().equals(mapping.grantId())
                || current.tenantGrantVersion() != mapping.grantVersion()
                || !current.tenantProviderVersion().equals(mapping.providerVersion())) {
            return AuthorizationResolution.denied(NotificationInvalidationReason.RECIPIENT_MAPPING);
        }
        NotificationAuthorizationFacts facts = new NotificationAuthorizationFacts(
                intent,
                new NotificationRecipientMappingId(mapping.id()),
                mapping.version(),
                new ProviderBindingId(mapping.providerBindingId()),
                mapping.providerBindingVersion(),
                new ConnectionId(mapping.connectionId()),
                mapping.connectionVersion(),
                new ConnectionGrantId(mapping.grantId()),
                mapping.grantVersion(),
                policyId(intent.organizationId(), intent.teamId()),
                policy.policyVersion(),
                preference);
        return AuthorizationResolution.allowed(facts);
    }

    private Optional<NotificationTemplate> currentTemplate(String serverTemplateKey) {
        Optional<TemplateRow> template = oneOptional(jdbc.query(
                """
                SELECT template_id, template_version, server_template_key
                FROM crewscope.notification_template
                WHERE server_template_key = ? AND status = 'PUBLISHED'
                ORDER BY template_version DESC LIMIT 1
                """,
                (row, ignored) -> new TemplateRow(
                        row.getObject("template_id", UUID.class),
                        row.getLong("template_version"),
                        row.getString("server_template_key")),
                serverTemplateKey));
        if (template.isEmpty()) {
            return Optional.empty();
        }
        TemplateRow row = template.orElseThrow();
        return materializeTemplate(row);
    }

    private Optional<TemplateCoordinates> existingIntentTemplate(
            ProjectionGenerationLease lease, InboxItem item) {
        return oneOptional(jdbc.query(
                """
                SELECT template_id, template_version
                FROM crewscope.notification_intent
                WHERE organization_id = ? AND projection_name = ? AND generation = ?
                  AND inbox_item_id = ?
                """,
                (row, ignored) -> new TemplateCoordinates(
                        row.getObject("template_id", UUID.class),
                        row.getLong("template_version")),
                item.organizationId().value(), lease.key().projectionName().value(),
                lease.key().generation().value(), item.id().value()));
    }

    private Optional<NotificationTemplate> template(
            TemplateCoordinates coordinates, String serverTemplateKey) {
        Optional<TemplateRow> result = oneOptional(jdbc.query(
                """
                SELECT template_id, template_version, server_template_key
                FROM crewscope.notification_template
                WHERE template_id = ? AND template_version = ?
                  AND server_template_key = ? AND status = 'PUBLISHED'
                """,
                (row, ignored) -> new TemplateRow(
                        row.getObject("template_id", UUID.class),
                        row.getLong("template_version"),
                        row.getString("server_template_key")),
                coordinates.id(), coordinates.version(), serverTemplateKey));
        return result.flatMap(this::materializeTemplate);
    }

    private Optional<NotificationTemplate> materializeTemplate(TemplateRow row) {
        Map<String, NotificationVariableSpec> schema = new LinkedHashMap<>();
        List<VariableRow> variables = jdbc.query(
                """
                SELECT variable_name, variable_type, maximum_length,
                       trusted_origins::TEXT AS trusted_origins
                FROM crewscope.notification_template_variable
                WHERE template_id = ? AND template_version = ?
                ORDER BY variable_name
                """,
                (result, ignored) -> new VariableRow(
                        result.getString("variable_name"), result.getString("variable_type"),
                        result.getInt("maximum_length"), result.getString("trusted_origins")),
                row.id(), row.version());
        try {
            for (VariableRow variable : variables) {
                if (!SAFE_VARIABLES.contains(variable.name())) {
                    return Optional.empty();
                }
                NotificationVariableSpec spec = variable.type().equals("TEXT")
                        ? NotificationVariableSpec.text(variable.name(), variable.maximumLength())
                        : NotificationVariableSpec.trustedLink(
                                variable.name(), variable.maximumLength(),
                                trustedOrigins(variable.trustedOrigins()));
                schema.put(variable.name(), spec);
            }
            return Optional.of(new NotificationTemplate(
                    new NotificationTemplateRef(
                            new NotificationTemplateId(row.id()),
                            new NotificationTemplateVersion(row.version())),
                    row.key(), schema, NotificationTemplateStatus.PUBLISHED));
        } catch (RuntimeException invalidTemplate) {
            return Optional.empty();
        }
    }

    private Optional<Map<String, String>> variables(
            ProjectedInbox projected, NotificationTemplate template) {
        InboxItem item = projected.item();
        Map<String, String> available = new LinkedHashMap<>();
        available.put("itemType", item.source().key().itemType().name());
        available.put("sourceType", item.source().key().sourceType().name());
        available.put("sourceId", item.source().key().sourceId().toString());
        available.put("sourceRevision", Long.toString(item.source().key().sourceRevision().value()));
        available.put("priority", item.source().priority().name());
        item.source().deadline().ifPresent(value -> available.put("deadline", value.toString()));
        String inboxPath = "/app/inbox/" + item.id();
        available.put("inboxUrl", publicUrl(inboxPath));
        available.put("sourceUrl", publicUrl(inboxPath));
        available.put("reviewUrl", publicUrl("/app/reviews/" + item.source().key().sourceId()));
        available.put("confirmationUrl", publicUrl(
                "/app/actions/" + item.source().key().sourceId()));
        available.put("taskUrl", publicUrl("/app/tasks/" + item.source().key().sourceId()));
        workItemTitle(item).ifPresent(value -> available.put("workItemTitle", value));
        Map<String, String> selected = new LinkedHashMap<>();
        for (String name : template.schema().keySet()) {
            String value = available.get(name);
            if (value == null) {
                return Optional.empty();
            }
            selected.put(name, value);
        }
        try {
            template.validateVariables(selected);
            return Optional.of(Map.copyOf(selected));
        } catch (RuntimeException invalidVariables) {
            return Optional.empty();
        }
    }

    private Optional<String> workItemTitle(InboxItem item) {
        String sql = switch (item.source().key().sourceType()) {
            case RESPONSIBILITY_ASSIGNMENT -> """
                    SELECT work_item.title FROM crewscope.responsibility_assignment source
                    JOIN crewscope.work_item work_item ON work_item.id = source.work_item_id
                    WHERE source.organization_id = ? AND source.id = ?
                    """;
            case REVIEW_REQUEST -> """
                    SELECT work_item.title FROM crewscope.review_request source
                    JOIN crewscope.task task ON task.id = source.task_id
                    JOIN crewscope.work_item work_item ON work_item.id = task.work_item_id
                    WHERE source.organization_id = ? AND source.id = ?
                    """;
            case ACTION_CONFIRMATION -> """
                    SELECT work_item.title FROM crewscope.action_bundle source
                    JOIN crewscope.work_item work_item ON work_item.id = source.work_item_id
                    WHERE source.organization_id = ? AND source.id = ?
                    """;
            case TASK_EXECUTION -> """
                    SELECT work_item.title FROM crewscope.task_execution source
                    JOIN crewscope.task task ON task.id = source.task_id
                    JOIN crewscope.work_item work_item ON work_item.id = task.work_item_id
                    WHERE source.organization_id = ? AND source.id = ?
                    """;
            case ACTION_DELIVERY -> """
                    SELECT work_item.title FROM crewscope.planned_action source
                    JOIN crewscope.action_bundle bundle ON bundle.id = source.action_bundle_id
                    JOIN crewscope.work_item work_item ON work_item.id = bundle.work_item_id
                    WHERE source.organization_id = ? AND source.id = ?
                    """;
            case NOTIFICATION_DELIVERY -> null;
        };
        if (sql == null) {
            return Optional.empty();
        }
        return oneOptional(jdbc.query(
                sql,
                (row, ignored) -> row.getString("title"),
                item.organizationId().value(), item.source().key().sourceId()));
    }

    private Optional<NotificationPreference> preference(InboxItem item) {
        return oneOptional(jdbc.query(
                """
                SELECT enabled, enabled_item_types::TEXT AS enabled_item_types,
                       muted_until, version
                FROM crewscope.notification_preference
                WHERE organization_id = ? AND team_id = ? AND member_id = ?
                """,
                (row, ignored) -> new NotificationPreference(
                        item.memberId(), row.getBoolean("enabled"),
                        itemTypes(row.getString("enabled_item_types")),
                        Optional.ofNullable(row.getObject("muted_until", OffsetDateTime.class))
                                .map(UtcTimestamp::from),
                        row.getLong("version")),
                item.organizationId().value(), item.teamId().value(), item.memberId().value()));
    }

    private void reconcileDeliveryFailures(
            ProjectionGenerationLease lease, TeamId teamId, UtcTimestamp now) {
        List<DeliveryOutcome> outcomes = jdbc.query(
                """
                SELECT delivery.delivery_id, delivery.status, delivery.version,
                       delivery.updated_at, action.recipient_member_id,
                       member.status AS member_status,
                       EXISTS (
                           SELECT 1 FROM crewscope.notification_delivery replacement
                           WHERE replacement.organization_id = delivery.organization_id
                             AND replacement.redelivery_of = delivery.delivery_id
                             AND replacement.status = 'SUCCEEDED'
                       ) AS recovered
                FROM crewscope.notification_delivery delivery
                JOIN crewscope.notification_planned_action action
                  ON action.organization_id = delivery.organization_id
                 AND action.action_id = delivery.action_id
                JOIN crewscope.team_member member
                  ON member.organization_id = action.organization_id
                 AND member.team_id = action.team_id
                 AND member.id = action.recipient_member_id
                WHERE delivery.organization_id = ? AND action.team_id = ?
                  AND (delivery.status = 'FAILED_FINAL' OR EXISTS (
                       SELECT 1 FROM crewscope.notification_delivery replacement
                       WHERE replacement.organization_id = delivery.organization_id
                         AND replacement.redelivery_of = delivery.delivery_id
                         AND replacement.status = 'SUCCEEDED'))
                ORDER BY delivery.created_at, delivery.delivery_id
                """,
                (row, ignored) -> new DeliveryOutcome(
                        row.getObject("delivery_id", UUID.class), row.getString("status"),
                        row.getLong("version"),
                        UtcTimestamp.from(row.getObject("updated_at", OffsetDateTime.class)),
                        row.getObject("recipient_member_id", UUID.class),
                        row.getString("member_status"), row.getBoolean("recovered")),
                lease.key().organizationId().value(), teamId.value());
        for (DeliveryOutcome outcome : outcomes) {
            projectDeliveryFailure(lease, teamId, outcome, now);
        }
    }

    private void projectDeliveryFailure(
            ProjectionGenerationLease lease,
            TeamId teamId,
            DeliveryOutcome outcome,
            UtcTimestamp now) {
        InboxSourceKey key = new InboxSourceKey(
                lease.key().organizationId(), new TeamMemberId(outcome.memberId()),
                InboxItemType.EXCEPTION, InboxSourceType.NOTIFICATION_DELIVERY,
                outcome.deliveryId(), InboxSourceRevision.INITIAL);
        InboxSource source = InboxSource.open(
                key, InboxPriority.URGENT, Optional.empty(), outcome.updatedAt());
        boolean closed = outcome.recovered() || !outcome.memberStatus().equals("ACTIVE");
        if (closed) {
            InboxCloseReason reason = outcome.recovered()
                    ? InboxCloseReason.EXCEPTION_RESOLVED
                    : InboxCloseReason.MEMBER_NO_LONGER_ELIGIBLE;
            source = source.close(reason, max(outcome.updatedAt(), now));
        }
        InboxItem item = InboxItem.project(
                teamId, lease.key().projectionName(), lease.key().generation(),
                InboxEventProjector.DEFINITION.projectionSchemaVersion(), source);
        if (!closed) {
            insertInbox(item);
        } else {
            insertClosedInbox(item);
            jdbc.update(
                    """
                    UPDATE crewscope.inbox_item
                    SET source_status = 'CLOSED', close_reason = ?, closed_at = ?
                    WHERE organization_id = ? AND projection_name = ? AND generation = ?
                      AND inbox_item_id = ? AND source_status = 'OPEN'
                    """,
                    source.closeReason().orElseThrow().name(),
                    source.closedAt().orElseThrow().toOffsetDateTime(),
                    item.organizationId().value(), item.projectionName().value(),
                    item.projectionGeneration().value(), item.id().value());
        }
    }

    private void insertInbox(InboxItem item) {
        InboxSource source = item.source();
        jdbc.update(
                """
                INSERT INTO crewscope.inbox_item (
                    organization_id, team_id, member_id, projection_name, generation,
                    inbox_item_id, projection_schema_version, item_type, source_type,
                    source_id, source_revision, priority, deadline, opened_at,
                    source_status, close_reason, closed_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', NULL, NULL)
                ON CONFLICT (organization_id, projection_name, generation, inbox_item_id)
                DO NOTHING
                """,
                item.organizationId().value(), item.teamId().value(), item.memberId().value(),
                item.projectionName().value(), item.projectionGeneration().value(), item.id().value(),
                item.projectionSchemaVersion().value(), source.key().itemType().name(),
                source.key().sourceType().name(), source.key().sourceId(),
                source.key().sourceRevision().value(), source.priority().name(), null,
                source.openedAt().toOffsetDateTime());
    }

    private void insertClosedInbox(InboxItem item) {
        InboxSource source = item.source();
        jdbc.update(
                """
                INSERT INTO crewscope.inbox_item (
                    organization_id, team_id, member_id, projection_name, generation,
                    inbox_item_id, projection_schema_version, item_type, source_type,
                    source_id, source_revision, priority, deadline, opened_at,
                    source_status, close_reason, closed_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'CLOSED', ?, ?)
                ON CONFLICT (organization_id, projection_name, generation, inbox_item_id)
                DO NOTHING
                """,
                item.organizationId().value(), item.teamId().value(), item.memberId().value(),
                item.projectionName().value(), item.projectionGeneration().value(), item.id().value(),
                item.projectionSchemaVersion().value(), source.key().itemType().name(),
                source.key().sourceType().name(), source.key().sourceId(),
                source.key().sourceRevision().value(), source.priority().name(), null,
                source.openedAt().toOffsetDateTime(), source.closeReason().orElseThrow().name(),
                source.closedAt().orElseThrow().toOffsetDateTime());
    }

    private void invalidateLatest(
            OrganizationId organizationId,
            NotificationIntentId intentId,
            NotificationInvalidationReason reason,
            UtcTimestamp now) {
        latestPlan(organizationId, intentId)
                .filter(plan -> !plan.terminal())
                .ifPresent(plan -> invalidate(plan, reason, now));
    }

    private void invalidate(
            LatestPlan plan, NotificationInvalidationReason reason, UtcTimestamp now) {
        UUID receiptId = UUID.nameUUIDFromBytes(
                ("notification-invalidation-v1:" + plan.deliveryId() + ':' + reason)
                        .getBytes(StandardCharsets.UTF_8));
        jdbc.update(
                """
                INSERT INTO crewscope.notification_receipt (
                    organization_id, receipt_id, delivery_id, action_id, action_digest,
                    deduplication_key, result, failure_code, provider_receipt_hash,
                    provider_message_hash, evidence_code, received_at
                ) VALUES (?, ?, ?, ?, ?, ?, 'INVALIDATED', NULL, NULL, NULL, ?, ?)
                ON CONFLICT (organization_id, delivery_id) DO NOTHING
                """,
                plan.organizationId(), receiptId, plan.deliveryId(), plan.actionId(),
                plan.actionDigest(), plan.deduplicationKey(),
                "AUTHORIZATION_" + reason.name(), now.toOffsetDateTime());
        jdbc.update(
                """
                UPDATE crewscope.notification_planned_action
                SET status = 'INVALIDATED', invalidation_reason = ?,
                    version = version + 1, updated_at = ?
                WHERE organization_id = ? AND action_id = ? AND status = 'PLANNED'
                """,
                reason.name(), now.toOffsetDateTime(), plan.organizationId(), plan.actionId());
        jdbc.update(
                """
                UPDATE crewscope.notification_delivery
                SET status = 'INVALIDATED', next_attempt_at = NULL,
                    invalidation_reason = ?, receipt_id = ?,
                    version = version + 1, updated_at = ?
                WHERE organization_id = ? AND delivery_id = ?
                  AND status IN ('READY', 'RUNNING', 'RETRY_WAIT', 'UNKNOWN', 'RECONCILING')
                """,
                reason.name(), receiptId, now.toOffsetDateTime(),
                plan.organizationId(), plan.deliveryId());
    }

    private Optional<LatestPlan> latestPlan(
            OrganizationId organizationId, NotificationIntentId intentId) {
        return oneOptional(jdbc.query(
                """
                SELECT action.organization_id, action.action_id, action.intent_id,
                       action.source_identity_hash, action.template_id, action.template_version,
                       action.variable_hash, action.recipient_mapping_id,
                       action.recipient_mapping_version, action.provider_binding_id,
                       action.provider_binding_version, action.connection_id,
                       action.connection_version, action.connection_grant_id,
                       action.connection_grant_version, action.team_policy_id,
                       action.team_policy_version, action.preference_version,
                       action.authorization_digest, action.action_digest,
                       action.deduplication_key, delivery.delivery_id, delivery.status
                FROM crewscope.notification_planned_action action
                JOIN crewscope.notification_delivery delivery
                  ON delivery.organization_id = action.organization_id
                 AND delivery.action_id = action.action_id
                WHERE action.organization_id = ? AND action.intent_id = ?
                ORDER BY action.created_at DESC, action.action_id DESC
                LIMIT 1
                """,
                (row, ignored) -> latest(row),
                organizationId.value(), intentId.value()));
    }

    private NotificationInvalidationReason driftReason(
            LatestPlan previous, NotificationAuthorizationFacts facts) {
        if (!previous.sourceIdentityHash().equals(TaskFactHash.sha256(
                facts.intent().sourceKey().canonicalIdentity()).toString())) {
            return NotificationInvalidationReason.SOURCE;
        }
        if (!previous.templateId().equals(facts.intent().template().templateId().value())
                || previous.templateVersion() != facts.intent().template().version().value()) {
            return NotificationInvalidationReason.TEMPLATE;
        }
        if (!previous.variableHash().equals(facts.intent().variables().hash().toString())) {
            return NotificationInvalidationReason.VARIABLES;
        }
        if (!previous.mappingId().equals(facts.recipientMappingId().value())
                || previous.mappingVersion() != facts.recipientMappingVersion()) {
            return NotificationInvalidationReason.RECIPIENT_MAPPING;
        }
        if (!previous.bindingId().equals(facts.providerBindingId().value())
                || previous.bindingVersion() != facts.providerBindingVersion()) {
            return NotificationInvalidationReason.PROVIDER_BINDING;
        }
        if (!previous.connectionId().equals(facts.connectionId().value())
                || previous.connectionVersion() != facts.connectionVersion()) {
            return NotificationInvalidationReason.CONNECTION;
        }
        if (!previous.grantId().equals(facts.grantId().value())
                || previous.grantVersion() != facts.grantVersion()) {
            return NotificationInvalidationReason.GRANT;
        }
        if (!previous.policyId().equals(facts.teamPolicyId().value())
                || previous.policyVersion() != facts.teamPolicyVersion()) {
            return NotificationInvalidationReason.TEAM_POLICY;
        }
        return NotificationInvalidationReason.MEMBER_PREFERENCE;
    }

    private boolean isActiveGeneration(ProjectionGenerationLease lease) {
        Integer count = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM crewscope.projection_pointer pointer
                JOIN crewscope.projection_generation generation
                  ON generation.organization_id = pointer.organization_id
                 AND generation.projection_name = pointer.projection_name
                 AND generation.generation = pointer.active_generation
                WHERE pointer.organization_id = ? AND pointer.projection_name = ?
                  AND pointer.active_generation = ? AND generation.status = 'ACTIVE'
                  AND generation.fencing_token = ?
                """,
                Integer.class,
                lease.key().organizationId().value(), lease.key().projectionName().value(),
                lease.key().generation().value(), lease.fencingToken().value());
        return count != null && count == 1;
    }

    private ProjectedInbox projectedInbox(ResultSet row) throws SQLException {
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
        InboxItem item = new InboxItem(
                new InboxItemId(row.getObject("inbox_item_id", UUID.class)),
                new TeamId(row.getObject("team_id", UUID.class)),
                new io.crewscope.domain.projection.ProjectionName(row.getString("projection_name")),
                new io.crewscope.domain.projection.ProjectionGeneration(row.getLong("generation")),
                new SchemaVersion(row.getInt("projection_schema_version")), source);
        return new ProjectedInbox(item);
    }

    private MappingRow mapping(ResultSet row) throws SQLException {
        return new MappingRow(
                row.getObject("id", UUID.class), row.getString("status"), row.getLong("version"),
                row.getObject("provider_binding_id", UUID.class),
                row.getLong("provider_binding_version"),
                row.getObject("connection_id", UUID.class), row.getLong("connection_version"),
                row.getObject("connection_grant_id", UUID.class),
                row.getLong("connection_grant_version"),
                row.getObject("external_tenant_id", UUID.class),
                row.getLong("external_tenant_version"), row.getString("provider_version"),
                row.getString("member_status"));
    }

    private AuthorizationRow authorization(ResultSet row) throws SQLException {
        return new AuthorizationRow(
                row.getString("binding_status"), row.getLong("binding_version"),
                row.getObject("binding_connection_id", UUID.class),
                row.getLong("binding_connection_version"),
                row.getObject("binding_grant_id", UUID.class),
                row.getLong("binding_grant_version"), row.getString("provider_type"),
                row.getBoolean("binding_capable"), row.getString("connection_status"),
                row.getLong("current_connection_version"),
                nullableTimestamp(row, "connection_expires_at"), row.getString("grant_status"),
                row.getLong("current_grant_version"),
                UtcTimestamp.from(row.getObject("valid_from", OffsetDateTime.class)),
                nullableTimestamp(row, "grant_expires_at"), row.getBoolean("grant_capable"),
                row.getString("tenant_status"), row.getLong("current_tenant_version"),
                row.getObject("tenant_connection_id", UUID.class),
                row.getLong("tenant_connection_version"),
                row.getObject("tenant_grant_id", UUID.class),
                row.getLong("tenant_grant_version"), row.getString("tenant_provider_version"));
    }

    private LatestPlan latest(ResultSet row) throws SQLException {
        String status = row.getString("status");
        return new LatestPlan(
                row.getObject("organization_id", UUID.class),
                row.getObject("action_id", UUID.class), row.getObject("intent_id", UUID.class),
                row.getString("source_identity_hash"), row.getObject("template_id", UUID.class),
                row.getLong("template_version"), row.getString("variable_hash"),
                row.getObject("recipient_mapping_id", UUID.class),
                row.getLong("recipient_mapping_version"),
                row.getObject("provider_binding_id", UUID.class),
                row.getLong("provider_binding_version"), row.getObject("connection_id", UUID.class),
                row.getLong("connection_version"),
                row.getObject("connection_grant_id", UUID.class),
                row.getLong("connection_grant_version"),
                row.getObject("team_policy_id", UUID.class), row.getLong("team_policy_version"),
                row.getLong("preference_version"), row.getString("authorization_digest"),
                row.getString("action_digest"), row.getString("deduplication_key"),
                row.getObject("delivery_id", UUID.class), status,
                Set.of("SUCCEEDED", "FAILED_FINAL", "INVALIDATED", "CANCELLED").contains(status));
    }

    private Set<TrustedNotificationOrigin> trustedOrigins(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            if (root == null || !root.isArray() || root.isEmpty()) {
                throw new IllegalArgumentException("Trusted origins must be a non-empty array");
            }
            Set<TrustedNotificationOrigin> origins = new java.util.LinkedHashSet<>();
            for (JsonNode value : root) {
                if (value.isString()) {
                    URI uri = URI.create(value.stringValue());
                    origins.add(new TrustedNotificationOrigin(
                            uri.getScheme(), uri.getHost(), uri.getPort()));
                } else if (value.isObject()) {
                    origins.add(new TrustedNotificationOrigin(
                            value.path("scheme").stringValue(), value.path("host").stringValue(),
                            value.path("port").isMissingNode() ? -1 : value.path("port").intValue()));
                } else {
                    throw new IllegalArgumentException("Trusted origin shape is invalid");
                }
            }
            return Set.copyOf(origins);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Trusted origins are invalid", exception);
        }
    }

    private Set<InboxItemType> itemTypes(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            if (root == null || !root.isArray()) {
                throw new IllegalArgumentException("Enabled Inbox item types must be an array");
            }
            EnumSet<InboxItemType> values = EnumSet.noneOf(InboxItemType.class);
            for (JsonNode value : root) {
                if (!value.isString()) {
                    throw new IllegalArgumentException("Enabled Inbox item type must be a string");
                }
                values.add(InboxItemType.valueOf(value.stringValue()));
            }
            return Set.copyOf(values);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Enabled Inbox item types are invalid", exception);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Notification variables cannot be serialized", exception);
        }
    }

    private String capabilityJson() {
        return json(List.of(FIXED_TEMPLATE_CAPABILITY));
    }

    private String publicUrl(String path) {
        return publicBaseUri.resolve(path).toString();
    }

    private static URI requirePublicBaseUri(String value) {
        URI uri = URI.create(Objects.requireNonNull(value, "publicBaseUri")).normalize();
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null || (uri.getPath() != null
                && !uri.getPath().isEmpty() && !uri.getPath().equals("/"))) {
            throw new IllegalArgumentException(
                    "crewscope.notification.public-base-uri must be an HTTPS origin");
        }
        return URI.create("https://" + uri.getHost() + (uri.getPort() == -1 ? "" : ":" + uri.getPort()));
    }

    private static TeamNotificationPolicyId policyId(
            OrganizationId organizationId, TeamId teamId) {
        String value = "team-notification-policy-v1:" + organizationId + ':' + teamId;
        return new TeamNotificationPolicyId(UUID.nameUUIDFromBytes(
                value.getBytes(StandardCharsets.UTF_8)));
    }

    private static Optional<UtcTimestamp> nullableTimestamp(ResultSet row, String column)
            throws SQLException {
        return Optional.ofNullable(row.getObject(column, OffsetDateTime.class))
                .map(UtcTimestamp::from);
    }

    private static UtcTimestamp max(UtcTimestamp left, UtcTimestamp right) {
        return left.compareTo(right) >= 0 ? left : right;
    }

    private static <T> T one(List<T> values, String label) {
        if (values.size() != 1) {
            throw new IllegalStateException(label + " query must return exactly one row");
        }
        return values.get(0);
    }

    private static <T> Optional<T> oneOptional(List<T> values) {
        if (values.size() > 1) {
            throw new IllegalStateException("Notification projection query returned multiple rows");
        }
        return values.stream().findFirst();
    }

    private record ProjectedInbox(InboxItem item) {}

    private record TemplateRow(UUID id, long version, String key) {}

    private record TemplateCoordinates(UUID id, long version) {}

    private record VariableRow(
            String name, String type, int maximumLength, String trustedOrigins) {}

    private record MappingRow(
            UUID id,
            String status,
            long version,
            UUID providerBindingId,
            long providerBindingVersion,
            UUID connectionId,
            long connectionVersion,
            UUID grantId,
            long grantVersion,
            UUID externalTenantId,
            long externalTenantVersion,
            String providerVersion,
            String memberStatus) {}

    private record AuthorizationRow(
            String bindingStatus,
            long bindingVersion,
            UUID bindingConnectionId,
            long bindingConnectionVersion,
            UUID bindingGrantId,
            long bindingGrantVersion,
            String providerType,
            boolean bindingCapable,
            String connectionStatus,
            long connectionVersion,
            Optional<UtcTimestamp> connectionExpiresAt,
            String grantStatus,
            long grantVersion,
            UtcTimestamp validFrom,
            Optional<UtcTimestamp> grantExpiresAt,
            boolean grantCapable,
            String tenantStatus,
            long tenantVersion,
            UUID tenantConnectionId,
            long tenantConnectionVersion,
            UUID tenantGrantId,
            long tenantGrantVersion,
            String tenantProviderVersion) {}

    private record AuthorizationResolution(
            Optional<NotificationAuthorizationFacts> facts,
            NotificationInvalidationReason failureReason) {
        static AuthorizationResolution allowed(NotificationAuthorizationFacts facts) {
            return new AuthorizationResolution(
                    Optional.of(Objects.requireNonNull(facts, "facts")),
                    NotificationInvalidationReason.SOURCE);
        }

        static AuthorizationResolution denied(NotificationInvalidationReason reason) {
            return new AuthorizationResolution(Optional.empty(), Objects.requireNonNull(reason, "reason"));
        }
    }

    private record DeliveryOutcome(
            UUID deliveryId,
            String status,
            long version,
            UtcTimestamp updatedAt,
            UUID memberId,
            String memberStatus,
            boolean recovered) {}

    private record LatestPlan(
            UUID organizationId,
            UUID actionId,
            UUID intentId,
            String sourceIdentityHash,
            UUID templateId,
            long templateVersion,
            String variableHash,
            UUID mappingId,
            long mappingVersion,
            UUID bindingId,
            long bindingVersion,
            UUID connectionId,
            long connectionVersion,
            UUID grantId,
            long grantVersion,
            UUID policyId,
            long policyVersion,
            long preferenceVersion,
            String authorizationDigest,
            String actionDigest,
            String deduplicationKey,
            UUID deliveryId,
            String deliveryStatus,
            boolean terminal) {}
}
