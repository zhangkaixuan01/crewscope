package io.crewscope.infrastructure.credential;

import io.crewscope.application.credential.CredentialSecret;
import io.crewscope.application.credential.CredentialStoreError;
import io.crewscope.application.credential.CredentialStoreException;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** AES-256-GCM primitive that normalizes all decryption failures to one safe error. */
final class CredentialEnvelopeCrypto {

    static final String ALGORITHM = "AES-256-GCM";
    static final String AAD_VERSION = "1";

    private static final String CIPHER_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int NONCE_SIZE = 12;
    private static final int TAG_SIZE = 16;
    private static final int TAG_BITS = TAG_SIZE * 8;

    private final CredentialKeyRing keyRing;
    private final SecureRandom secureRandom;
    private final CredentialAadCodec aadCodec;

    CredentialEnvelopeCrypto(
            CredentialEncryptionKey encryptionKey,
            SecureRandom secureRandom,
            CredentialAadCodec aadCodec) {
        this(CredentialKeyRing.single(encryptionKey), secureRandom, aadCodec);
    }

    CredentialEnvelopeCrypto(
            CredentialKeyRing keyRing,
            SecureRandom secureRandom,
            CredentialAadCodec aadCodec) {
        this.keyRing = Objects.requireNonNull(keyRing, "keyRing");
        this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom");
        this.aadCodec = Objects.requireNonNull(aadCodec, "aadCodec");
    }

    CredentialEnvelope encrypt(CredentialEnvelopeContext context, CredentialSecret secret) {
        byte[] plaintext = Objects.requireNonNull(secret, "secret").copyBytes();
        byte[] key = keyRing.currentKey().copyKeyBytes();
        byte[] nonce = new byte[NONCE_SIZE];
        secureRandom.nextBytes(nonce);
        try {
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, key, nonce, context);
            byte[] combined = cipher.doFinal(plaintext);
            int ciphertextSize = combined.length - TAG_SIZE;
            return new CredentialEnvelope(
                    Arrays.copyOf(combined, ciphertextSize),
                    nonce,
                    Arrays.copyOfRange(combined, ciphertextSize, combined.length));
        } catch (GeneralSecurityException exception) {
            throw new CredentialStoreException(
                    CredentialStoreError.STORAGE_FAILURE,
                    "Credential encryption failed",
                    exception);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
            Arrays.fill(key, (byte) 0);
        }
    }

    CredentialSecret decrypt(CredentialEnvelopeContext context, CredentialEnvelope envelope) {
        CredentialEncryptionKey decryptionKey =
                keyRing.find(context.keyId()).orElseThrow(() -> integrityFailure(null));
        byte[] key = decryptionKey.copyKeyBytes();
        byte[] ciphertext = envelope.ciphertext();
        byte[] tag = envelope.authenticationTag();
        byte[] combined = new byte[ciphertext.length + tag.length];
        System.arraycopy(ciphertext, 0, combined, 0, ciphertext.length);
        System.arraycopy(tag, 0, combined, ciphertext.length, tag.length);
        byte[] plaintext = null;
        try {
            if (envelope.nonce().length != NONCE_SIZE || tag.length != TAG_SIZE) {
                throw integrityFailure(null);
            }
            Cipher cipher = cipher(Cipher.DECRYPT_MODE, key, envelope.nonce(), context);
            plaintext = cipher.doFinal(combined);
            return CredentialSecret.of(plaintext);
        } catch (CredentialStoreException exception) {
            throw exception;
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw integrityFailure(exception);
        } finally {
            Arrays.fill(key, (byte) 0);
            Arrays.fill(combined, (byte) 0);
            if (plaintext != null) {
                Arrays.fill(plaintext, (byte) 0);
            }
        }
    }

    /** Replaces a revoked envelope with random bytes without materializing its plaintext. */
    CredentialEnvelope destroy(int ciphertextSize) {
        if (ciphertextSize < 1) {
            throw new IllegalArgumentException("ciphertextSize must be positive");
        }
        byte[] ciphertext = new byte[ciphertextSize];
        byte[] nonce = new byte[NONCE_SIZE];
        byte[] tag = new byte[TAG_SIZE];
        secureRandom.nextBytes(ciphertext);
        secureRandom.nextBytes(nonce);
        secureRandom.nextBytes(tag);
        return new CredentialEnvelope(ciphertext, nonce, tag);
    }

    private Cipher cipher(
            int mode, byte[] key, byte[] nonce, CredentialEnvelopeContext context)
            throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
        cipher.updateAAD(aadCodec.encode(context));
        return cipher;
    }

    private static CredentialStoreException integrityFailure(Throwable cause) {
        if (cause == null) {
            return new CredentialStoreException(
                    CredentialStoreError.INTEGRITY_VIOLATION,
                    "Credential envelope could not be authenticated");
        }
        return new CredentialStoreException(
                CredentialStoreError.INTEGRITY_VIOLATION,
                "Credential envelope could not be authenticated",
                cause);
    }
}
