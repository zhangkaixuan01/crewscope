package io.crewscope.domain.agent;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Canonical SHA-256 digest for immutable Agent template capability and policy facts. */
public record AgentTemplateHash(String value) {

    public AgentTemplateHash {
        value = Objects.requireNonNull(value, "value");
        if (!value.matches("[0-9a-f]{64}")) {
            throw new DomainValidationException(
                    "agentTemplate.hash", "must be a lower-case SHA-256 digest");
        }
    }

    public static AgentTemplateHash sha256(String canonicalValue) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(Objects.requireNonNull(canonicalValue, "canonicalValue")
                            .getBytes(StandardCharsets.UTF_8));
            return new AgentTemplateHash(HexFormat.of().formatHex(digest));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    static void append(StringBuilder target, String value) {
        String requiredValue = Objects.requireNonNull(value, "value");
        target.append('|').append(requiredValue.length()).append(':').append(requiredValue);
    }

    @Override
    public String toString() {
        return value;
    }
}
