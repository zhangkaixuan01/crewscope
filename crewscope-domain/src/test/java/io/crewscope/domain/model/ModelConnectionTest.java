package io.crewscope.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.InvalidStateTransitionException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.CredentialId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ModelConnectionTest {

    private static final OrganizationId ORGANIZATION_ID = OrganizationId.generate();
    private static final TeamId TEAM_ID = TeamId.generate();
    private static final PrincipalId USER_ID = PrincipalId.generate();
    private static final PrincipalId ACTOR = PrincipalId.generate();
    private static final UtcTimestamp CREATED_AT =
            UtcTimestamp.parse("2026-08-23T01:00:00Z");
    private static final UtcTimestamp FIRST_CHECK =
            UtcTimestamp.parse("2026-08-23T01:01:00Z");
    private static final UtcTimestamp SECOND_CHECK =
            UtcTimestamp.parse("2026-08-23T01:02:00Z");

    @Test
    void enforcesOwnerCredentialAndBillingSubjectMatrix() {
        ModelProviderDefinition provider = provider();
        ModelConnectionOwner userOwner = userOwner(USER_ID);
        ModelConnectionOwner teamOwner = teamOwner(TEAM_ID);
        ModelConnectionOwner organizationOwner =
                ModelConnectionOwner.organization(ORGANIZATION_ID);

        assertEquals(
                ModelConnectionOwnerType.USER,
                open(
                                provider,
                                userOwner,
                                ModelCredentialSubject.principal(ORGANIZATION_ID, USER_ID),
                                ModelBillingSubject.principal(ORGANIZATION_ID, USER_ID))
                        .owner()
                        .type());
        assertEquals(
                ModelSubjectType.ORGANIZATION,
                open(
                                provider,
                                teamOwner,
                                ModelCredentialSubject.organization(ORGANIZATION_ID),
                                ModelBillingSubject.organization(ORGANIZATION_ID))
                        .credentialBinding()
                        .subject()
                        .type());
        assertEquals(
                ModelConnectionOwnerType.ORGANIZATION,
                open(
                                provider,
                                organizationOwner,
                                ModelCredentialSubject.organization(ORGANIZATION_ID),
                                ModelBillingSubject.organization(ORGANIZATION_ID))
                        .owner()
                        .type());

        assertThrows(
                DomainValidationException.class,
                () -> open(
                        provider,
                        userOwner,
                        ModelCredentialSubject.organization(ORGANIZATION_ID),
                        ModelBillingSubject.principal(ORGANIZATION_ID, USER_ID)));
        assertThrows(
                DomainValidationException.class,
                () -> open(
                        provider,
                        teamOwner,
                        ModelCredentialSubject.team(ORGANIZATION_ID, TeamId.generate()),
                        ModelBillingSubject.team(ORGANIZATION_ID, TEAM_ID)));
        assertThrows(
                DomainValidationException.class,
                () -> open(
                        provider,
                        organizationOwner,
                        ModelCredentialSubject.organization(ORGANIZATION_ID),
                        ModelBillingSubject.team(ORGANIZATION_ID, TEAM_ID)));
    }

    @Test
    void rejectsCrossOrganizationSubjectsAndUnavailableProviderRegions() {
        ModelProviderDefinition provider = provider();
        OrganizationId otherOrganization = OrganizationId.generate();

        assertThrows(
                DomainValidationException.class,
                () -> open(
                        provider,
                        userOwner(USER_ID),
                        ModelCredentialSubject.principal(otherOrganization, USER_ID),
                        ModelBillingSubject.principal(ORGANIZATION_ID, USER_ID)));
        assertThrows(
                DomainValidationException.class,
                () -> ModelConnection.open(
                        provider,
                        ModelConnectionId.generate(),
                        userOwner(USER_ID),
                        new ModelEndpoint("https://gateway.example.com/v1"),
                        new ModelRegion("eu"),
                        credential(ModelCredentialSubject.principal(ORGANIZATION_ID, USER_ID)),
                        ModelBillingSubject.principal(ORGANIZATION_ID, USER_ID),
                        ACTOR,
                        CREATED_AT));
    }

    @Test
    void verificationControlsSelectionAndKeepsOnlySanitizedFailureFacts() {
        ModelProviderDefinition provider = provider();
        ModelConnection connection = userConnection(provider);

        assertEquals(ModelConnectionHealthStatus.UNKNOWN, connection.health().status());
        assertThrows(
                DomainValidationException.class,
                () -> connection.requireSelectable(provider));

        ModelConnection healthy = connection.recordVerificationSuccess(
                provider,
                0,
                new ModelCredentialVersion(0),
                ACTOR,
                FIRST_CHECK);
        healthy.requireSelectable(provider);
        assertEquals(ModelConnectionHealthStatus.HEALTHY, healthy.health().status());
        assertEquals(Optional.of(FIRST_CHECK), healthy.health().lastHealthyAt());

        ModelConnection unhealthy = healthy.recordVerificationFailure(
                provider,
                1,
                new ModelCredentialVersion(0),
                ModelConnectionHealthFailureCode.AUTHENTICATION_FAILED,
                ACTOR,
                SECOND_CHECK);
        assertEquals(ModelConnectionHealthStatus.UNHEALTHY, unhealthy.health().status());
        assertEquals(1, unhealthy.health().consecutiveFailures());
        assertEquals(Optional.of(FIRST_CHECK), unhealthy.health().lastHealthyAt());
        assertEquals(
                Optional.of(ModelConnectionHealthFailureCode.AUTHENTICATION_FAILED),
                unhealthy.health().failureCode());
        assertThrows(
                DomainValidationException.class,
                () -> unhealthy.requireSelectable(provider));
        assertFalse(unhealthy.toString().contains("provider-secret-response"));
    }

    @Test
    void rotationKeepsStableIdentityAndRejectsAStaleHealthProbe() {
        ModelProviderDefinition provider = provider();
        ModelConnection healthy = userConnection(provider).recordVerificationSuccess(
                provider,
                0,
                new ModelCredentialVersion(0),
                ACTOR,
                FIRST_CHECK);

        ModelConnection rotated = healthy.rotateCredential(
                1, new ModelCredentialVersion(1), ACTOR, SECOND_CHECK);

        assertEquals(healthy.id(), rotated.id());
        assertEquals(
                healthy.credentialBinding().credentialId(),
                rotated.credentialBinding().credentialId());
        assertEquals(
                healthy.credentialBinding().subject(),
                rotated.credentialBinding().subject());
        assertEquals(healthy.owner(), rotated.owner());
        assertEquals(healthy.providerKey(), rotated.providerKey());
        assertEquals(new ModelCredentialVersion(1), rotated.credentialBinding().credentialVersion());
        assertEquals(ModelConnectionHealthStatus.UNKNOWN, rotated.health().status());
        assertThrows(
                OptimisticLockConflictException.class,
                () -> rotated.recordVerificationSuccess(
                        provider,
                        1,
                        new ModelCredentialVersion(0),
                        ACTOR,
                        UtcTimestamp.parse("2026-08-23T01:03:00Z")));
        assertThrows(
                DomainValidationException.class,
                () -> rotated.rotateCredential(
                        2,
                        new ModelCredentialVersion(3),
                        ACTOR,
                        UtcTimestamp.parse("2026-08-23T01:03:00Z")));
    }

    @Test
    void rejectsOutOfOrderHealthAndWrongCredentialVersion() {
        ModelProviderDefinition provider = provider();
        ModelConnection healthy = userConnection(provider).recordVerificationSuccess(
                provider,
                0,
                new ModelCredentialVersion(0),
                ACTOR,
                FIRST_CHECK);

        assertThrows(
                DomainValidationException.class,
                () -> healthy.recordVerificationFailure(
                        provider,
                        1,
                        new ModelCredentialVersion(0),
                        ModelConnectionHealthFailureCode.TIMEOUT,
                        ACTOR,
                        FIRST_CHECK));
        assertThrows(
                DomainValidationException.class,
                () -> healthy.recordVerificationFailure(
                        provider,
                        1,
                        new ModelCredentialVersion(1),
                        ModelConnectionHealthFailureCode.TIMEOUT,
                        ACTOR,
                        SECOND_CHECK));
        assertThrows(
                OptimisticLockConflictException.class,
                () -> healthy.recordVerificationSuccess(
                        provider,
                        0,
                        new ModelCredentialVersion(0),
                        ACTOR,
                        SECOND_CHECK));
    }

    @Test
    void suspendActivateAndRevokeUseStrongVersionsAndTerminalState() {
        ModelProviderDefinition provider = provider();
        ModelConnection healthy = userConnection(provider).recordVerificationSuccess(
                provider,
                0,
                new ModelCredentialVersion(0),
                ACTOR,
                FIRST_CHECK);
        ModelConnection suspended = healthy.suspend(1, ACTOR, SECOND_CHECK);

        assertEquals(ModelConnectionStatus.SUSPENDED, suspended.status());
        assertThrows(
                OptimisticLockConflictException.class,
                () -> suspended.activate(provider, 1, ACTOR, SECOND_CHECK));

        ModelConnection active = suspended.activate(
                provider,
                2,
                ACTOR,
                UtcTimestamp.parse("2026-08-23T01:03:00Z"));
        ModelConnection revoked = active.revoke(
                3,
                ModelConnectionRevocationReason.OWNER_REQUESTED,
                ACTOR,
                UtcTimestamp.parse("2026-08-23T01:04:00Z"));

        assertEquals(ModelConnectionStatus.REVOKED, revoked.status());
        assertEquals(
                Optional.of(ModelConnectionRevocationReason.OWNER_REQUESTED),
                revoked.revocationReason());
        assertThrows(
                InvalidStateTransitionException.class,
                () -> revoked.rotateCredential(
                        4,
                        new ModelCredentialVersion(1),
                        ACTOR,
                        UtcTimestamp.parse("2026-08-23T01:05:00Z")));
    }

    @Test
    void reconstitutionRejectsForgedProviderHealthAndSecretShapedFields() {
        ModelProviderDefinition provider = provider();
        ModelConnection connection = userConnection(provider);

        assertThrows(
                DomainValidationException.class,
                () -> ModelConnection.reconstitute(
                        provider,
                        ModelRegistryHash.sha256("forged-provider"),
                        connection.id(),
                        connection.organizationId(),
                        connection.owner(),
                        connection.endpoint(),
                        connection.region(),
                        connection.credentialBinding(),
                        connection.billingSubject(),
                        connection.status(),
                        connection.health(),
                        connection.revocationReason(),
                        connection.version(),
                        connection.audit()));
        assertThrows(
                DomainValidationException.class,
                () -> ModelConnection.reconstitute(
                        provider,
                        connection.providerDefinitionHash(),
                        connection.id(),
                        connection.organizationId(),
                        connection.owner(),
                        connection.endpoint(),
                        connection.region(),
                        connection.credentialBinding(),
                        connection.billingSubject(),
                        connection.status(),
                        ModelConnectionHealth.unknown(new ModelCredentialVersion(1)),
                        connection.revocationReason(),
                        connection.version(),
                        connection.audit()));

        Set<String> forbiddenNames = Set.of("secret", "apikey", "token", "header", "password");
        assertTrue(Arrays.stream(ModelCredentialBinding.class.getRecordComponents())
                .map(RecordComponent::getName)
                .map(String::toLowerCase)
                .noneMatch(name -> forbiddenNames.stream().anyMatch(name::contains)));
        assertTrue(Arrays.stream(ModelCredentialBinding.class.getRecordComponents())
                .map(RecordComponent::getType)
                .noneMatch(type -> type.equals(byte[].class) || type.equals(char[].class)));
    }

    private static ModelConnection userConnection(ModelProviderDefinition provider) {
        return open(
                provider,
                userOwner(USER_ID),
                ModelCredentialSubject.principal(ORGANIZATION_ID, USER_ID),
                ModelBillingSubject.principal(ORGANIZATION_ID, USER_ID));
    }

    private static ModelConnection open(
            ModelProviderDefinition provider,
            ModelConnectionOwner owner,
            ModelCredentialSubject credentialSubject,
            ModelBillingSubject billingSubject) {
        return ModelConnection.open(
                provider,
                ModelConnectionId.generate(),
                owner,
                new ModelEndpoint("https://gateway.example.com/v1"),
                new ModelRegion("global"),
                credential(credentialSubject),
                billingSubject,
                ACTOR,
                CREATED_AT);
    }

    private static ModelCredentialBinding credential(ModelCredentialSubject subject) {
        return new ModelCredentialBinding(
                CredentialId.generate(), subject, new ModelCredentialVersion(0));
    }

    private static ModelConnectionOwner userOwner(PrincipalId principalId) {
        return new ModelConnectionOwner(
                ORGANIZATION_ID,
                ModelConnectionOwnerType.USER,
                principalId.value(),
                Optional.empty(),
                Optional.of(principalId));
    }

    private static ModelConnectionOwner teamOwner(TeamId teamId) {
        return new ModelConnectionOwner(
                ORGANIZATION_ID,
                ModelConnectionOwnerType.TEAM,
                teamId.value(),
                Optional.of(teamId),
                Optional.empty());
    }

    private static ModelProviderDefinition provider() {
        return ModelProviderDefinition.publish(
                new ModelProviderKey("deepseek"),
                "DeepSeek",
                new ModelAdapterKey("openai-compatible"),
                new ModelEndpoint("https://api.deepseek.com/v1"),
                Set.of(new ModelRegion("global"), new ModelRegion("cn")),
                ModelDataPolicy.noRetention(),
                ACTOR,
                CREATED_AT);
    }
}
