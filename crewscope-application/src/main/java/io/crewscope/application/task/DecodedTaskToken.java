package io.crewscope.application.task;

import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.runtime.RuntimeEnvironment;
import io.crewscope.domain.task.TaskCredentialGrantId;
import io.crewscope.domain.task.TaskTokenJti;
import java.util.Objects;

/** Signature-verified minimum JWT envelope; persisted Grant facts remain authoritative. */
public record DecodedTaskToken(
        String audience,
        TaskCredentialGrantId grantId,
        TaskTokenJti jti,
        PrincipalId subject,
        OrganizationId organizationId,
        RuntimeEnvironment environment,
        String scopeFingerprint,
        UtcTimestamp issuedAt,
        UtcTimestamp expiresAt) {

    public DecodedTaskToken {
        audience = Objects.requireNonNull(audience, "audience");
        grantId = Objects.requireNonNull(grantId, "grantId");
        jti = Objects.requireNonNull(jti, "jti");
        subject = Objects.requireNonNull(subject, "subject");
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        environment = Objects.requireNonNull(environment, "environment");
        scopeFingerprint = Objects.requireNonNull(scopeFingerprint, "scopeFingerprint");
        issuedAt = Objects.requireNonNull(issuedAt, "issuedAt");
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
    }

    @Override
    public String toString() {
        return "DecodedTaskToken[grantId=" + grantId + ", token=[REDACTED]]";
    }
}
