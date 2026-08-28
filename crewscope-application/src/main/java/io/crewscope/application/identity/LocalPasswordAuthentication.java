package io.crewscope.application.identity;

import io.crewscope.domain.identity.LocalPasswordHash;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/** Asynchronous password boundary that keeps expensive work off request and Event Loop threads. */
public interface LocalPasswordAuthentication {

    /** Validates the registration/change-password policy and writes the current Hash format. */
    CompletionStage<LocalPasswordHash> encodeForStorage(String rawPassword);

    /** Performs exactly one real or Dummy Match and safely upgrades an eligible legacy Hash. */
    CompletionStage<LocalPasswordVerification> verify(
            String rawPassword,
            Optional<LocalCredentialAuthenticationMaterial> credential,
            boolean accountCanAuthenticate);
}
