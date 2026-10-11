package io.crewscope.application.github;

import io.crewscope.application.credential.CredentialSubject;
import io.crewscope.domain.provider.ProviderOwner;
import java.util.Objects;

/** Connection owner and credential subject resolved for one create command. */
record GitHubConnectionOwnerFacts(ProviderOwner owner, CredentialSubject credentialSubject) {

    GitHubConnectionOwnerFacts {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(credentialSubject, "credentialSubject");
    }
}
