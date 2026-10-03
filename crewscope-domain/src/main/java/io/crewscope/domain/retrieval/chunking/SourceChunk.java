package io.crewscope.domain.retrieval.chunking;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * One chunk of one indexed source document (M10-S01 §3.3): a bounded slice of a single
 * file, addressable by 1-based inclusive line span. The content hash pins the chunk text
 * so stale chunks can never masquerade as fresh ones across generations.
 */
public record SourceChunk(
        String path,
        String language,
        int startLine,
        int endLine,
        String content,
        String contentHash) {

    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    public SourceChunk {
        if (path == null || path.isBlank()) {
            throw new DomainValidationException("sourceChunk.path", "must not be blank");
        }
        if (language == null || language.isBlank() || language.length() > 32) {
            throw new DomainValidationException(
                    "sourceChunk.language", "must be non-blank and at most 32 characters");
        }
        if (content == null) {
            throw new DomainValidationException("sourceChunk.content", "must not be null");
        }
        if (startLine < 1 || endLine < startLine) {
            throw new DomainValidationException(
                    "sourceChunk.lines", "requires 1 <= startLine <= endLine");
        }
        if (contentHash == null || !SHA_256.matcher(contentHash).matches()) {
            throw new DomainValidationException(
                    "sourceChunk.contentHash", "must be a 64-character lowercase SHA-256 value");
        }
    }

    public static SourceChunk of(
            String path, String language, int startLine, int endLine, String content) {
        return new SourceChunk(path, language, startLine, endLine, content, sha256(content));
    }

    static String sha256(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }
}
