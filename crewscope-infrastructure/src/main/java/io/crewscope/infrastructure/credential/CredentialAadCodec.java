package io.crewscope.infrastructure.credential;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

/** Deterministic length-prefixed AAD encoding without delimiter ambiguity. */
final class CredentialAadCodec {

    private static final String DOMAIN = "crewscope-credential-aad";

    byte[] encode(CredentialEnvelopeContext context) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                write(output, DOMAIN);
                write(output, context.aadVersion());
                write(output, context.credentialId().toString());
                write(output, context.subject().organizationId().toString());
                write(output, context.subject().type().name());
                write(output, context.subject().subjectId().toString());
                write(output, context.subject().teamId().map(Object::toString).orElse(null));
                write(output, context.subject().principalId().map(Object::toString).orElse(null));
                write(output, context.credentialKey());
                write(output, context.providerKey());
                write(output, context.connectionRef().map(Object::toString).orElse(null));
                write(output, context.credentialType());
                write(output, context.expiresAt().map(Object::toString).orElse(null));
                TreeMap<String, String> metadata = new TreeMap<>(context.metadata());
                output.writeInt(metadata.size());
                for (Map.Entry<String, String> entry : metadata.entrySet()) {
                    write(output, entry.getKey());
                    write(output, entry.getValue());
                }
                write(output, context.algorithm());
                write(output, context.keyId());
            }
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Credential AAD could not be encoded", exception);
        }
    }

    private static void write(DataOutputStream output, String value) throws IOException {
        if (value == null) {
            output.writeInt(-1);
            return;
        }
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(encoded.length);
        output.write(encoded);
    }
}
