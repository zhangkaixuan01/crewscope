package io.crewscope.domain.model;

import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.CredentialId;
import java.util.Objects;

/** Non-secret CredentialStore reference and exact version bound to one model connection. */
public record ModelCredentialBinding(
        CredentialId credentialId,
        ModelCredentialSubject subject,
        ModelCredentialVersion credentialVersion) {

    public ModelCredentialBinding {
        credentialId = Objects.requireNonNull(credentialId, "credentialId");
        subject = Objects.requireNonNull(subject, "subject");
        credentialVersion = Objects.requireNonNull(credentialVersion, "credentialVersion");
    }

    public ModelCredentialBinding rotate(ModelCredentialVersion nextVersion) {
        ModelCredentialVersion required = Objects.requireNonNull(nextVersion, "nextVersion");
        if (!credentialVersion.next().equals(required)) {
            throw new DomainValidationException(
                    "modelConnection.credentialVersion",
                    "must advance exactly one version during credential rotation");
        }
        return new ModelCredentialBinding(credentialId, subject, required);
    }
}
