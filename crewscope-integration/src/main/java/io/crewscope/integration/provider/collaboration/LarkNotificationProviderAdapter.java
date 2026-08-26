package io.crewscope.integration.provider.collaboration;

import io.crewscope.application.collaboration.LarkExternalTenantRepository;
import io.crewscope.application.collaboration.LarkMemberMappingRepository;
import io.crewscope.application.notification.FixedNotificationTemplateRenderer;
import io.crewscope.application.notification.NotificationCredentialHandle;
import io.crewscope.application.notification.NotificationProviderPort;
import io.crewscope.application.notification.NotificationProviderRequest;
import io.crewscope.application.notification.NotificationQueryResult;
import io.crewscope.application.notification.NotificationSendResult;
import io.crewscope.application.notification.RenderedNotificationMessage;
import io.crewscope.application.team.TeamMemberRepository;
import io.crewscope.domain.collaboration.CollaborationRecipient;
import io.crewscope.domain.collaboration.LarkConnectionAuthorization;
import io.crewscope.domain.collaboration.LarkExternalTenant;
import io.crewscope.domain.collaboration.LarkMemberMapping;
import io.crewscope.domain.collaboration.LarkMemberMappingId;
import io.crewscope.domain.notification.NotificationFailureCode;
import io.crewscope.domain.team.TeamMember;
import java.util.Objects;

/**
 * Fixed-template Lark notification adapter.
 *
 * <p>There is deliberately no arbitrary recipient, text, URL or HTTP method entry point. All
 * outbound material is reconstructed from a claimed action, current mapping and published template.
 */
public final class LarkNotificationProviderAdapter implements NotificationProviderPort {

    private final LarkOpenApiClient client;
    private final FixedNotificationTemplateRenderer renderer;
    private final LarkMemberMappingRepository mappings;
    private final LarkExternalTenantRepository tenants;
    private final TeamMemberRepository members;

    public LarkNotificationProviderAdapter(
            LarkOpenApiClient client,
            FixedNotificationTemplateRenderer renderer,
            LarkMemberMappingRepository mappings,
            LarkExternalTenantRepository tenants,
            TeamMemberRepository members) {
        this.client = Objects.requireNonNull(client, "client");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.mappings = Objects.requireNonNull(mappings, "mappings");
        this.tenants = Objects.requireNonNull(tenants, "tenants");
        this.members = Objects.requireNonNull(members, "members");
    }

    @Override
    public NotificationSendResult send(
            NotificationProviderRequest request, NotificationCredentialHandle credential) {
        Material material;
        try {
            material = prepare(request, credential);
        } catch (PreparationFailure failure) {
            return NotificationSendResult.failed(failure.failureCode, failure.evidenceCode);
        }
        try {
            LarkOpenApiClient.MessageResponse written = client.sendTextMessage(
                    material.credential,
                    new LarkTextMessageRequest(
                            material.recipient.openId(),
                            material.message.text(),
                            material.providerReference));
            LarkOpenApiClient.MessageResponse confirmed = client.queryMessage(
                    material.credential, written.messageId());
            LarkMessageReceiptProjection receipt = receipt(
                    material.providerReference, written).merge(
                            receipt(material.providerReference, confirmed));
            return NotificationSendResult.accepted(
                    receipt.providerReference().value(),
                    receipt.messageId().value(),
                    "LARK_MESSAGE_EXISTS");
        } catch (LarkProviderException failure) {
            return sendFailure(failure);
        }
    }

    @Override
    public NotificationQueryResult query(
            NotificationProviderRequest request, NotificationCredentialHandle credential) {
        Material material;
        try {
            material = prepare(request, credential);
        } catch (PreparationFailure failure) {
            return NotificationQueryResult.failed(failure.failureCode, failure.evidenceCode);
        }
        LarkOpenApiClient.MessageResponse recovered;
        try {
            // Lark exposes no lookup-by-uuid operation. Repeating this exact fixed request with the
            // same Provider UUID recovers the original message_id without creating another message.
            recovered = client.sendTextMessage(
                    material.credential,
                    new LarkTextMessageRequest(
                            material.recipient.openId(),
                            material.message.text(),
                            material.providerReference));
        } catch (LarkProviderException failure) {
            return queryWriteFailure(failure);
        }
        try {
            LarkOpenApiClient.MessageResponse confirmed = client.queryMessage(
                    material.credential, recovered.messageId());
            LarkMessageReceiptProjection receipt = receipt(
                    material.providerReference, recovered).merge(
                            receipt(material.providerReference, confirmed));
            return NotificationQueryResult.found(
                    receipt.providerReference().value(),
                    receipt.messageId().value(),
                    "LARK_MESSAGE_EXISTS");
        } catch (LarkProviderException failure) {
            if (failure.code() == LarkProviderErrorCode.RESOURCE_UNAVAILABLE) {
                return NotificationQueryResult.notFound("LARK_MESSAGE_NOT_FOUND");
            }
            return queryFailure(failure);
        }
    }

    private Material prepare(
            NotificationProviderRequest request, NotificationCredentialHandle credential) {
        NotificationProviderRequest required = Objects.requireNonNull(request, "request");
        if (!(credential instanceof LarkNotificationCredentialHandle larkCredential)
                || credential.isClosed()) {
            throw preparation(
                    NotificationFailureCode.AUTHORIZATION_REVOKED,
                    "LARK_NOTIFICATION_CREDENTIAL_REJECTED");
        }
        LarkConnectionAuthorization authorization = larkCredential.authorization();
        if (!required.organizationId().equals(authorization.organizationId())
                || !required.teamId().equals(authorization.teamId())
                || !required.authorization().providerBindingId()
                        .equals(authorization.providerBindingId())
                || required.authorization().providerBindingVersion()
                        != authorization.providerBindingVersion()
                || !required.connectionId().equals(authorization.connectionId())
                || required.authorization().connectionVersion()
                        != authorization.connectionVersion()
                || !required.authorization().grantId().equals(authorization.grantId())
                || required.authorization().grantVersion() != authorization.grantVersion()) {
            throw preparation(
                    NotificationFailureCode.AUTHORIZATION_REVOKED,
                    "LARK_NOTIFICATION_AUTHORIZATION_DRIFT");
        }
        LarkMemberMapping mapping = mappings.findById(
                        required.organizationId(),
                        new LarkMemberMappingId(required.recipientMappingId().value()))
                .filter(value -> value.version()
                        == required.authorization().recipientMappingVersion())
                .orElseThrow(() -> preparation(
                        NotificationFailureCode.RECIPIENT_UNAVAILABLE,
                        "LARK_NOTIFICATION_MAPPING_UNAVAILABLE"));
        TeamMember member = members.findById(
                        required.organizationId(), required.recipientMemberId())
                .filter(value -> value.scope().teamId().equals(required.teamId()))
                .orElseThrow(() -> preparation(
                        NotificationFailureCode.RECIPIENT_UNAVAILABLE,
                        "LARK_NOTIFICATION_MEMBER_UNAVAILABLE"));
        LarkExternalTenant tenant = tenants.findByConnection(
                        required.organizationId(), authorization.connectionId())
                .orElseThrow(() -> preparation(
                        NotificationFailureCode.AUTHORIZATION_REVOKED,
                        "LARK_NOTIFICATION_TENANT_UNAVAILABLE"));
        CollaborationRecipient recipient;
        try {
            recipient = mapping.resolveRecipient(member, authorization, tenant);
        } catch (RuntimeException staleRecipient) {
            throw preparation(
                    NotificationFailureCode.RECIPIENT_UNAVAILABLE,
                    "LARK_NOTIFICATION_RECIPIENT_STALE");
        }
        RenderedNotificationMessage message;
        try {
            message = renderer.render(required.template(), required.variables());
        } catch (RuntimeException invalidTemplate) {
            throw preparation(
                    NotificationFailureCode.PROVIDER_REJECTED,
                    "LARK_NOTIFICATION_TEMPLATE_REJECTED");
        }
        return new Material(
                larkCredential,
                recipient,
                message,
                LarkNotificationUuid.from(required.idempotencyKey()));
    }

    private static LarkMessageReceiptProjection receipt(
            LarkNotificationUuid providerReference,
            LarkOpenApiClient.MessageResponse response) {
        return new LarkMessageReceiptProjection(
                providerReference, response.messageId(), response.observedAt());
    }

    private static NotificationSendResult sendFailure(LarkProviderException failure) {
        return switch (failure.code()) {
            case RATE_LIMITED, PROVIDER_UNAVAILABLE ->
                    NotificationSendResult.retryable(failure.evidenceCode());
            case UNKNOWN_DELIVERY, INVALID_RESPONSE, CANCELLED ->
                    NotificationSendResult.unknown(failure.evidenceCode());
            case RESOURCE_UNAVAILABLE -> NotificationSendResult.failed(
                    NotificationFailureCode.RECIPIENT_UNAVAILABLE, failure.evidenceCode());
            case AUTHENTICATION_REQUIRED, PERMISSION_DENIED, IDENTITY_MISMATCH,
                    CONNECTION_UNAVAILABLE, CREDENTIAL_UNAVAILABLE ->
                    NotificationSendResult.failed(
                            NotificationFailureCode.AUTHORIZATION_REVOKED,
                            failure.evidenceCode());
        };
    }

    private static NotificationQueryResult queryWriteFailure(LarkProviderException failure) {
        return switch (failure.code()) {
            case RATE_LIMITED, PROVIDER_UNAVAILABLE ->
                    NotificationQueryResult.retryable(failure.evidenceCode());
            case UNKNOWN_DELIVERY, INVALID_RESPONSE, CANCELLED ->
                    NotificationQueryResult.unknown(failure.evidenceCode());
            case RESOURCE_UNAVAILABLE -> NotificationQueryResult.failed(
                    NotificationFailureCode.RECIPIENT_UNAVAILABLE, failure.evidenceCode());
            case AUTHENTICATION_REQUIRED, PERMISSION_DENIED, IDENTITY_MISMATCH,
                    CONNECTION_UNAVAILABLE, CREDENTIAL_UNAVAILABLE ->
                    NotificationQueryResult.failed(
                            NotificationFailureCode.AUTHORIZATION_REVOKED,
                            failure.evidenceCode());
        };
    }

    private static NotificationQueryResult queryFailure(LarkProviderException failure) {
        return switch (failure.code()) {
            case RATE_LIMITED, PROVIDER_UNAVAILABLE ->
                    NotificationQueryResult.retryable(failure.evidenceCode());
            case UNKNOWN_DELIVERY, INVALID_RESPONSE, CANCELLED ->
                    NotificationQueryResult.unknown(failure.evidenceCode());
            case RESOURCE_UNAVAILABLE ->
                    NotificationQueryResult.notFound(failure.evidenceCode());
            case AUTHENTICATION_REQUIRED, PERMISSION_DENIED, IDENTITY_MISMATCH,
                    CONNECTION_UNAVAILABLE, CREDENTIAL_UNAVAILABLE ->
                    NotificationQueryResult.failed(
                            NotificationFailureCode.AUTHORIZATION_REVOKED,
                            failure.evidenceCode());
        };
    }

    private static PreparationFailure preparation(
            NotificationFailureCode failureCode, String evidenceCode) {
        return new PreparationFailure(failureCode, evidenceCode);
    }

    private record Material(
            LarkNotificationCredentialHandle credential,
            CollaborationRecipient recipient,
            RenderedNotificationMessage message,
            LarkNotificationUuid providerReference) {}

    private static final class PreparationFailure extends RuntimeException {
        private final NotificationFailureCode failureCode;
        private final String evidenceCode;

        private PreparationFailure(
                NotificationFailureCode failureCode, String evidenceCode) {
            super("Lark notification preparation failed");
            this.failureCode = Objects.requireNonNull(failureCode, "failureCode");
            this.evidenceCode = Objects.requireNonNull(evidenceCode, "evidenceCode");
        }
    }
}
