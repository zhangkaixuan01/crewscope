package io.crewscope.integration.provider.collaboration;

import io.crewscope.application.collaboration.LarkIdentityVerificationPort;
import io.crewscope.application.collaboration.LarkMemberObservation;
import io.crewscope.application.collaboration.LarkProviderHealth;
import io.crewscope.application.collaboration.LarkProviderHealthPort;
import io.crewscope.application.collaboration.LarkProviderHealthStatus;
import io.crewscope.application.collaboration.LarkTenantObservation;
import io.crewscope.application.provider.CapabilityProvider;
import io.crewscope.application.provider.ProviderDescriptor;
import io.crewscope.domain.collaboration.LarkCollaborationCapabilities;
import io.crewscope.domain.collaboration.LarkConnectionAuthorization;
import io.crewscope.domain.collaboration.LarkExternalTenant;
import io.crewscope.domain.collaboration.LarkOpenId;
import io.crewscope.domain.collaboration.LarkProviderVersion;
import io.crewscope.domain.provider.ProviderCapabilities;
import io.crewscope.domain.provider.ProviderConnectionRequirement;
import io.crewscope.domain.provider.ProviderType;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.TimeProvider;
import java.util.Objects;
import java.util.Optional;

/** Lark Collaboration adapter exposing only fixed tenant and exact-member operations. */
public final class LarkCollaborationProvider
        implements CapabilityProvider, LarkIdentityVerificationPort, LarkProviderHealthPort {

    private static final LarkProviderVersion TENANT_PROVIDER_VERSION =
            new LarkProviderVersion("tenant-open-api-v1");

    private final Optional<LarkOpenApiClient> client;
    private final Optional<TimeProvider> timeProvider;

    /** Descriptor-only form retained when the network authorization graph is not configured. */
    public LarkCollaborationProvider() {
        this.client = Optional.empty();
        this.timeProvider = Optional.empty();
    }

    public LarkCollaborationProvider(LarkOpenApiClient client, TimeProvider timeProvider) {
        this.client = Optional.of(Objects.requireNonNull(client, "client"));
        this.timeProvider = Optional.of(Objects.requireNonNull(timeProvider, "timeProvider"));
    }

    @Override
    public ProviderDescriptor descriptor() {
        return new ProviderDescriptor(
                ProviderType.COLLABORATION,
                LarkCollaborationCapabilities.CONNECTOR_KEY,
                "1.0.0",
                "Lark");
    }

    public ProviderCapabilities capabilities() {
        return LarkCollaborationCapabilities.COMPLETE;
    }

    public ProviderConnectionRequirement connectionRequirement() {
        return ProviderConnectionRequirement.REQUIRED;
    }

    public Optional<String> connectorKey() {
        return Optional.of(LarkCollaborationCapabilities.CONNECTOR_KEY);
    }

    @Override
    public LarkTenantObservation verifyTenant(
            LarkConnectionAuthorization authorization, PrincipalId actor) {
        LarkOpenApiClient.TenantResponse response = requireClient().queryTenant(
                new LarkApiCallContext(
                        Objects.requireNonNull(authorization, "authorization"),
                        Objects.requireNonNull(actor, "actor")));
        return new LarkTenantObservation(
                response.tenantKey(), TENANT_PROVIDER_VERSION, response.observedAt());
    }

    @Override
    public LarkMemberObservation verifyMember(
            LarkConnectionAuthorization authorization,
            LarkExternalTenant tenant,
            LarkOpenId exactOpenId,
            PrincipalId actor) {
        LarkConnectionAuthorization current = Objects.requireNonNull(
                authorization, "authorization");
        if (!Objects.requireNonNull(tenant, "tenant").isCurrent(current)) {
            throw LarkProviderException.of(
                    LarkProviderErrorCode.IDENTITY_MISMATCH,
                    "Lark tenant evidence is not current for the exact authorization",
                    "LARK_TENANT_EVIDENCE_STALE");
        }
        LarkOpenApiClient.MemberResponse response = requireClient().queryMember(
                new LarkApiCallContext(current, Objects.requireNonNull(actor, "actor")),
                Objects.requireNonNull(exactOpenId, "exactOpenId"));
        return new LarkMemberObservation(
                response.openId(),
                response.unionId(),
                response.providerVersion(),
                response.observedAt());
    }

    @Override
    public LarkProviderHealth checkHealth(
            LarkConnectionAuthorization authorization, PrincipalId actor) {
        try {
            LarkOpenApiClient.TenantResponse response = requireClient().queryTenant(
                    new LarkApiCallContext(
                            Objects.requireNonNull(authorization, "authorization"),
                            Objects.requireNonNull(actor, "actor")));
            return LarkProviderHealth.healthy(response.observedAt());
        } catch (LarkProviderException failure) {
            return new LarkProviderHealth(
                    status(failure.code()),
                    failure.retryable(),
                    failure.retryAfter(),
                    failure.evidenceCode(),
                    timeProvider.orElseThrow(() -> failure).now());
        }
    }

    private LarkOpenApiClient requireClient() {
        return client.orElseThrow(() -> LarkProviderException.of(
                LarkProviderErrorCode.CONNECTION_UNAVAILABLE,
                "Lark Connector is not configured",
                "LARK_CONNECTOR_NOT_CONFIGURED"));
    }

    private static LarkProviderHealthStatus status(LarkProviderErrorCode code) {
        return switch (Objects.requireNonNull(code, "code")) {
            case AUTHENTICATION_REQUIRED -> LarkProviderHealthStatus.AUTHENTICATION_REQUIRED;
            case PERMISSION_DENIED -> LarkProviderHealthStatus.PERMISSION_DENIED;
            case RESOURCE_UNAVAILABLE -> LarkProviderHealthStatus.RESOURCE_UNAVAILABLE;
            case RATE_LIMITED -> LarkProviderHealthStatus.RATE_LIMITED;
            case PROVIDER_UNAVAILABLE, UNKNOWN_DELIVERY ->
                    LarkProviderHealthStatus.PROVIDER_UNAVAILABLE;
            case INVALID_RESPONSE -> LarkProviderHealthStatus.INVALID_RESPONSE;
            case IDENTITY_MISMATCH -> LarkProviderHealthStatus.IDENTITY_MISMATCH;
            case CONNECTION_UNAVAILABLE -> LarkProviderHealthStatus.CONNECTION_UNAVAILABLE;
            case CREDENTIAL_UNAVAILABLE -> LarkProviderHealthStatus.CREDENTIAL_UNAVAILABLE;
            case CANCELLED -> LarkProviderHealthStatus.CANCELLED;
        };
    }
}
