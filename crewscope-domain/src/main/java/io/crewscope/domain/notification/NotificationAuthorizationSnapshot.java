package io.crewscope.domain.notification;

import io.crewscope.domain.inbox.InboxSourceKey;
import io.crewscope.domain.provider.ConnectionGrantId;
import io.crewscope.domain.provider.ConnectionId;
import io.crewscope.domain.provider.ProviderBindingId;
import io.crewscope.domain.task.TaskFactHash;
import java.util.Objects;
import java.util.Optional;

/** Immutable policy-preauthorization snapshot used for preflight drift detection. */
public final class NotificationAuthorizationSnapshot {

    private final NotificationAuthorizationMode mode;
    private final NotificationIntentId intentId;
    private final InboxSourceKey sourceKey;
    private final NotificationTemplateRef template;
    private final NotificationVariableHash variableHash;
    private final NotificationRecipientMappingId recipientMappingId;
    private final long recipientMappingVersion;
    private final ProviderBindingId providerBindingId;
    private final long providerBindingVersion;
    private final ConnectionId connectionId;
    private final long connectionVersion;
    private final ConnectionGrantId grantId;
    private final long grantVersion;
    private final TeamNotificationPolicyId teamPolicyId;
    private final long teamPolicyVersion;
    private final long preferenceVersion;
    private final NotificationDeduplicationKey deduplicationKey;
    private final NotificationAuthorizationDigest digest;

    private NotificationAuthorizationSnapshot(
            NotificationAuthorizationFacts facts, String deduplicationNamespace) {
        NotificationAuthorizationFacts required = Objects.requireNonNull(facts, "facts");
        this.mode = NotificationAuthorizationMode.POLICY_PREAUTHORIZED;
        this.intentId = required.intent().id();
        this.sourceKey = required.intent().sourceKey();
        this.template = required.intent().template();
        this.variableHash = required.intent().variables().hash();
        this.recipientMappingId = required.recipientMappingId();
        this.recipientMappingVersion = required.recipientMappingVersion();
        this.providerBindingId = required.providerBindingId();
        this.providerBindingVersion = required.providerBindingVersion();
        this.connectionId = required.connectionId();
        this.connectionVersion = required.connectionVersion();
        this.grantId = required.grantId();
        this.grantVersion = required.grantVersion();
        this.teamPolicyId = required.teamPolicyId();
        this.teamPolicyVersion = required.teamPolicyVersion();
        this.preferenceVersion = required.preference().version();
        String factsCanonical = canonicalFacts();
        this.deduplicationKey = new NotificationDeduplicationKey(TaskFactHash.sha256(
                "notification-dedup-v1|" + encode(deduplicationNamespace) + factsCanonical));
        this.digest = new NotificationAuthorizationDigest(TaskFactHash.sha256(
                "notification-authorization-v1" + factsCanonical
                        + encode(this.deduplicationKey.toString())));
    }

    public static NotificationAuthorizationSnapshot captureAutomatic(
            NotificationAuthorizationFacts facts) {
        return new NotificationAuthorizationSnapshot(facts, "automatic");
    }

    /** A command-scoped namespace proves a redelivery is an explicit new external send. */
    public static NotificationAuthorizationSnapshot captureRedelivery(
            NotificationAuthorizationFacts facts,
            NotificationDeliveryId originalDeliveryId,
            NotificationRedeliveryCommandId commandId) {
        return new NotificationAuthorizationSnapshot(
                facts,
                "redelivery:" + Objects.requireNonNull(originalDeliveryId, "originalDeliveryId")
                        + ':' + Objects.requireNonNull(commandId, "commandId"));
    }

    /** Returns the first current server fact that differs from this immutable snapshot. */
    public Optional<NotificationInvalidationReason> invalidationReason(
            NotificationAuthorizationFacts current) {
        NotificationAuthorizationFacts value = Objects.requireNonNull(current, "current");
        if (!intentId.equals(value.intent().id()) || !sourceKey.equals(value.intent().sourceKey())) {
            return Optional.of(NotificationInvalidationReason.SOURCE);
        }
        if (!template.equals(value.intent().template())) {
            return Optional.of(NotificationInvalidationReason.TEMPLATE);
        }
        if (!variableHash.equals(value.intent().variables().hash())) {
            return Optional.of(NotificationInvalidationReason.VARIABLES);
        }
        if (!recipientMappingId.equals(value.recipientMappingId())
                || recipientMappingVersion != value.recipientMappingVersion()) {
            return Optional.of(NotificationInvalidationReason.RECIPIENT_MAPPING);
        }
        if (!providerBindingId.equals(value.providerBindingId())
                || providerBindingVersion != value.providerBindingVersion()) {
            return Optional.of(NotificationInvalidationReason.PROVIDER_BINDING);
        }
        if (!connectionId.equals(value.connectionId())
                || connectionVersion != value.connectionVersion()) {
            return Optional.of(NotificationInvalidationReason.CONNECTION);
        }
        if (!grantId.equals(value.grantId()) || grantVersion != value.grantVersion()) {
            return Optional.of(NotificationInvalidationReason.GRANT);
        }
        if (!teamPolicyId.equals(value.teamPolicyId())
                || teamPolicyVersion != value.teamPolicyVersion()) {
            return Optional.of(NotificationInvalidationReason.TEAM_POLICY);
        }
        if (preferenceVersion != value.preference().version()) {
            return Optional.of(NotificationInvalidationReason.MEMBER_PREFERENCE);
        }
        return Optional.empty();
    }

    private String canonicalFacts() {
        return encode(
                mode.name(),
                intentId.toString(),
                sourceKey.canonicalIdentity(),
                template.templateId().toString(),
                Long.toString(template.version().value()),
                variableHash.toString(),
                recipientMappingId.toString(),
                Long.toString(recipientMappingVersion),
                providerBindingId.toString(),
                Long.toString(providerBindingVersion),
                connectionId.toString(),
                Long.toString(connectionVersion),
                grantId.toString(),
                Long.toString(grantVersion),
                teamPolicyId.toString(),
                Long.toString(teamPolicyVersion),
                Long.toString(preferenceVersion));
    }

    private static String encode(String... values) {
        StringBuilder encoded = new StringBuilder();
        for (String value : values) {
            encoded.append('|').append(value.length()).append(':').append(value);
        }
        return encoded.toString();
    }

    public NotificationAuthorizationMode mode() { return mode; }
    public NotificationIntentId intentId() { return intentId; }
    public InboxSourceKey sourceKey() { return sourceKey; }
    public NotificationTemplateRef template() { return template; }
    public NotificationVariableHash variableHash() { return variableHash; }
    public NotificationRecipientMappingId recipientMappingId() { return recipientMappingId; }
    public long recipientMappingVersion() { return recipientMappingVersion; }
    public ProviderBindingId providerBindingId() { return providerBindingId; }
    public long providerBindingVersion() { return providerBindingVersion; }
    public ConnectionId connectionId() { return connectionId; }
    public long connectionVersion() { return connectionVersion; }
    public ConnectionGrantId grantId() { return grantId; }
    public long grantVersion() { return grantVersion; }
    public TeamNotificationPolicyId teamPolicyId() { return teamPolicyId; }
    public long teamPolicyVersion() { return teamPolicyVersion; }
    public long preferenceVersion() { return preferenceVersion; }
    public NotificationDeduplicationKey deduplicationKey() { return deduplicationKey; }
    public NotificationAuthorizationDigest digest() { return digest; }
}
