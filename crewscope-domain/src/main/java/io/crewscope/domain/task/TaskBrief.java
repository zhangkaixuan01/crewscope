package io.crewscope.domain.task;

import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.workitem.WorkItem;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Immutable objective and acceptance criteria copied into a Task at creation time. */
public record TaskBrief(String objective, List<String> acceptanceCriteria) {

    public static final int MAX_OBJECTIVE_LENGTH = 10_000;
    public static final int MAX_ACCEPTANCE_CRITERIA = 100;
    public static final int MAX_ACCEPTANCE_CRITERION_LENGTH = 10_000;
    public static final int MAX_TOTAL_ACCEPTANCE_CRITERIA_LENGTH = 100_000;

    public TaskBrief {
        objective = requireText(objective, "taskBrief.objective", MAX_OBJECTIVE_LENGTH);
        acceptanceCriteria = List.copyOf(
                Objects.requireNonNull(acceptanceCriteria, "acceptanceCriteria").stream()
                        .map(value -> requireText(
                                value,
                                "taskBrief.acceptanceCriteria",
                                MAX_ACCEPTANCE_CRITERION_LENGTH))
                        .toList());
        if (acceptanceCriteria.size() > MAX_ACCEPTANCE_CRITERIA
                || acceptanceCriteria.stream().mapToInt(String::length).sum()
                        > MAX_TOTAL_ACCEPTANCE_CRITERIA_LENGTH) {
            throw new DomainValidationException(
                    "taskBrief.acceptanceCriteria", "exceeds the supported Task input size");
        }
    }

    /** Provides a compatible brief for domain callers that delegate the current WorkItem as-is. */
    public static TaskBrief fromWorkItem(WorkItem workItem) {
        WorkItem required = Objects.requireNonNull(workItem, "workItem");
        return new TaskBrief(required.title(), required.description().stream().toList());
    }

    /** Fingerprints the exact immutable brief without ambiguous field boundaries. */
    public TaskFactHash contentHash() {
        MessageDigest digest = sha256();
        update(digest, objective);
        update(digest, Integer.toString(acceptanceCriteria.size()));
        acceptanceCriteria.forEach(value -> update(digest, value));
        return new TaskFactHash(HexFormat.of().formatHex(digest.digest()));
    }

    private static String requireText(String value, String field, int maximumLength) {
        String normalized = Objects.requireNonNull(value, field).strip();
        if (normalized.isEmpty() || normalized.length() > maximumLength) {
            throw new DomainValidationException(field, "must contain bounded non-blank text");
        }
        return normalized;
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
