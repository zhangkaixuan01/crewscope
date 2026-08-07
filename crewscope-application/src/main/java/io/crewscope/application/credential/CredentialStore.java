package io.crewscope.application.credential;

import java.util.Optional;

/** Storage boundary for encrypted long-lived credentials and short-lived plaintext handles. */
public interface CredentialStore {

    CredentialDescriptor create(CredentialCreateRequest request, CredentialSecret secret);

    Optional<ResolvedCredential> resolve(
            CredentialReference reference, CredentialAccessContext accessContext);

    CredentialDescriptor rotate(
            CredentialReference reference,
            long expectedVersion,
            CredentialMutationContext mutationContext,
            CredentialSecret newSecret);

    CredentialDescriptor revoke(
            CredentialReference reference,
            long expectedVersion,
            CredentialMutationContext mutationContext,
            CredentialRevocationReason reason);
}
