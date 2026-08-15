package io.crewscope.infrastructure.runtime;

import io.crewscope.application.task.TaskTokenJtiGenerator;
import io.crewscope.domain.task.TaskTokenJti;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;

/** Secure 256-bit Base64URL JTI generator with no process or time-derived components. */
public final class SecureTaskTokenJtiGenerator implements TaskTokenJtiGenerator {

    private final SecureRandom random;

    public SecureTaskTokenJtiGenerator() {
        this(new SecureRandom());
    }

    SecureTaskTokenJtiGenerator(SecureRandom random) {
        this.random = Objects.requireNonNull(random, "random");
    }

    @Override
    public TaskTokenJti generate() {
        byte[] entropy = new byte[32];
        random.nextBytes(entropy);
        return new TaskTokenJti(Base64.getUrlEncoder().withoutPadding().encodeToString(entropy));
    }
}
