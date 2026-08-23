package io.crewscope.infrastructure.persistence.review;

import io.crewscope.domain.agent.AgentConfigurationHash;
import io.crewscope.domain.agent.AgentConfigurationRevision;
import io.crewscope.domain.agent.AgentTemplateHash;
import io.crewscope.domain.agent.AgentTemplateVersion;
import io.crewscope.domain.coding.AcceptanceResult;
import io.crewscope.domain.coding.AcceptanceStatus;
import io.crewscope.domain.coding.CodingTargetSnapshotId;
import io.crewscope.domain.coding.CodingTargetSnapshotReference;
import io.crewscope.domain.coding.CommandEvidenceId;
import io.crewscope.domain.coding.CommandEvidenceReference;
import io.crewscope.domain.coding.CommandKind;
import io.crewscope.domain.coding.CommandTermination;
import io.crewscope.domain.coding.DiffArtifactId;
import io.crewscope.domain.coding.DiffArtifactReference;
import io.crewscope.domain.coding.DiffGeneration;
import io.crewscope.domain.coding.DiffPath;
import io.crewscope.domain.coding.EvidenceFailureClassification;
import io.crewscope.domain.coding.EvidenceSequence;
import io.crewscope.domain.coding.EvidenceSummary;
import io.crewscope.domain.coding.PatchArtifactReference;
import io.crewscope.domain.coding.RepositoryCommitId;
import io.crewscope.domain.coding.TestEvidenceId;
import io.crewscope.domain.review.ContextPackage;
import io.crewscope.domain.review.ReviewCommandEvidenceReference;
import io.crewscope.domain.review.ReviewDiffHunk;
import io.crewscope.domain.review.ReviewDiffReference;
import io.crewscope.domain.review.ReviewSubject;
import io.crewscope.domain.review.ReviewTestEvidenceReference;
import io.crewscope.domain.review.ReviewerExecutionReference;
import io.crewscope.domain.review.ReviewerRelationship;
import io.crewscope.domain.shared.id.ArtifactId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.task.PolicySnapshotId;
import io.crewscope.domain.task.RuntimeContentHash;
import io.crewscope.domain.task.TaskFactHash;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.workspace.AgentProfileId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Explicit non-secret JSONB codec for ContextPackage fields not represented as scalar columns. */
@Component
final class ReviewContextAuthorityJsonCodec {

    private static final long SCHEMA_VERSION = 1;

    private final ObjectMapper objectMapper;

    ReviewContextAuthorityJsonCodec(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    String encode(ContextPackage context) {
        ContextPackage value = Objects.requireNonNull(context, "context");
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("diff", encodeDiff(value.diff()));
        root.put("hunks", value.hunks().stream().map(this::encodeHunk).toList());
        root.put("testEvidence", encodeTest(value.testEvidence()));
        root.put("reviewer", encodeReviewer(value.reviewer()));
        return objectMapper.writeValueAsString(root);
    }

    ContextAuthority decode(String json, ReviewSubject subject) {
        Map<String, Object> root = readMap(json);
        if (number(root, "schemaVersion") != SCHEMA_VERSION) {
            throw new IllegalStateException("Unsupported Review authority snapshot schema");
        }
        ReviewDiffReference diff = decodeDiff(map(root, "diff"), subject);
        List<ReviewDiffHunk> hunks = listOfMaps(root, "hunks").stream()
                .map(this::decodeHunk)
                .toList();
        ReviewTestEvidenceReference test = decodeTest(map(root, "testEvidence"), subject);
        ReviewerExecutionReference reviewer = decodeReviewer(map(root, "reviewer"), subject);
        return new ContextAuthority(diff, hunks, test, reviewer);
    }

    private Map<String, Object> encodeDiff(ReviewDiffReference value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("artifactId", value.artifact().id().toString());
        result.put("finalHash", value.artifact().finalHash().value());
        result.put("codingTargetSnapshotId", value.codingTarget().snapshotId().toString());
        result.put("codingTargetRevision", value.codingTarget().revision());
        result.put("codingTargetHash", value.codingTarget().snapshotHash().value());
        result.put("baselineCommit", value.baselineCommit().value());
        result.put("deliveryCommit", value.deliveryCommit().value());
        result.put("generation", value.generation().value());
        result.put("manifestHash", value.manifestHash().value());
        result.put("patchArtifactId", value.patchArtifact().artifactId().toString());
        result.put("patchSizeBytes", value.patchArtifact().sizeBytes());
        result.put("patchSha256", value.patchArtifact().patchSha256().value());
        result.put("changedPaths", value.changedPaths().stream().map(DiffPath::value).toList());
        return result;
    }

    private ReviewDiffReference decodeDiff(Map<String, Object> value, ReviewSubject subject) {
        return new ReviewDiffReference(
                subject.scope(),
                subject.taskId(),
                subject.taskExecutionId(),
                subject.attempt(),
                new DiffArtifactReference(
                        new DiffArtifactId(uuid(value, "artifactId")),
                        new TaskFactHash(text(value, "finalHash"))),
                codingTarget(value),
                new RepositoryCommitId(text(value, "baselineCommit")),
                new RepositoryCommitId(text(value, "deliveryCommit")),
                new DiffGeneration(number(value, "generation")),
                new RuntimeContentHash(text(value, "manifestHash")),
                new PatchArtifactReference(
                        new ArtifactId(uuid(value, "patchArtifactId")),
                        number(value, "patchSizeBytes"),
                        new RuntimeContentHash(text(value, "patchSha256"))),
                stringList(value, "changedPaths").stream().map(DiffPath::new).toList());
    }

    private Map<String, Object> encodeHunk(ReviewDiffHunk value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", value.path().value());
        result.put("startLine", value.startLine());
        result.put("endLine", value.endLine());
        result.put("patchHash", value.patchHash().value());
        result.put("patch", value.patch().orElse(null));
        return result;
    }

    private ReviewDiffHunk decodeHunk(Map<String, Object> value) {
        return new ReviewDiffHunk(
                new DiffPath(text(value, "path")),
                integer(value, "startLine"),
                integer(value, "endLine"),
                new RuntimeContentHash(text(value, "patchHash")),
                optionalText(value, "patch"));
    }

    private Map<String, Object> encodeTest(ReviewTestEvidenceReference value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("codingTargetSnapshotId", value.codingTarget().snapshotId().toString());
        result.put("codingTargetRevision", value.codingTarget().revision());
        result.put("codingTargetHash", value.codingTarget().snapshotHash().value());
        result.put("id", value.id().toString());
        result.put("evidenceHash", value.evidenceHash().value());
        result.put("diffGeneration", value.diffGeneration().value());
        result.put("diffManifestHash", value.diffManifestHash().value());
        result.put("commands", value.commands().stream().map(this::encodeCommand).toList());
        result.put("acceptanceResults", value.acceptanceResults().stream()
                .map(this::encodeAcceptance).toList());
        return result;
    }

    private ReviewTestEvidenceReference decodeTest(
            Map<String, Object> value, ReviewSubject subject) {
        List<ReviewCommandEvidenceReference> commands = listOfMaps(value, "commands").stream()
                .map(this::decodeCommand)
                .toList();
        Map<UUID, CommandEvidenceReference> evidence = new LinkedHashMap<>();
        commands.forEach(command -> evidence.put(command.evidence().id().value(), command.evidence()));
        List<AcceptanceResult> acceptance = listOfMaps(value, "acceptanceResults").stream()
                .map(item -> decodeAcceptance(item, evidence))
                .toList();
        return new ReviewTestEvidenceReference(
                subject.scope(),
                subject.taskId(),
                subject.taskExecutionId(),
                subject.attempt(),
                codingTarget(value),
                new TestEvidenceId(uuid(value, "id")),
                new TaskFactHash(text(value, "evidenceHash")),
                new DiffGeneration(number(value, "diffGeneration")),
                new RuntimeContentHash(text(value, "diffManifestHash")),
                commands,
                acceptance);
    }

    private Map<String, Object> encodeCommand(ReviewCommandEvidenceReference value) {
        Map<String, Object> result = encodeEvidence(value.evidence());
        result.put("commandKind", value.commandKind().name());
        result.put("termination", value.termination().name());
        result.put("exitCode", value.exitCode().orElse(null));
        result.put("summary", value.summary().value());
        return result;
    }

    private ReviewCommandEvidenceReference decodeCommand(Map<String, Object> value) {
        return new ReviewCommandEvidenceReference(
                decodeEvidence(value),
                CommandKind.valueOf(text(value, "commandKind")),
                CommandTermination.valueOf(text(value, "termination")),
                optionalInteger(value, "exitCode"),
                new EvidenceSummary(text(value, "summary")));
    }

    private Map<String, Object> encodeAcceptance(AcceptanceResult value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("criterionIndex", value.criterionIndex());
        result.put("criterion", value.criterion());
        result.put("status", value.status().name());
        result.put("evidence", value.evidence().stream().map(this::encodeEvidence).toList());
        result.put("summary", value.summary().value());
        return result;
    }

    private AcceptanceResult decodeAcceptance(
            Map<String, Object> value, Map<UUID, CommandEvidenceReference> available) {
        List<CommandEvidenceReference> evidence = new ArrayList<>();
        for (Map<String, Object> item : listOfMaps(value, "evidence")) {
            CommandEvidenceReference decoded = decodeEvidence(item);
            CommandEvidenceReference canonical = available.get(decoded.id().value());
            if (!decoded.equals(canonical)) {
                throw new IllegalStateException(
                        "AcceptanceResult references unknown or drifted CommandEvidence");
            }
            evidence.add(canonical);
        }
        return new AcceptanceResult(
                integer(value, "criterionIndex"),
                text(value, "criterion"),
                AcceptanceStatus.valueOf(text(value, "status")),
                evidence,
                new EvidenceSummary(text(value, "summary")));
    }

    private Map<String, Object> encodeEvidence(CommandEvidenceReference value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", value.id().toString());
        result.put("sequence", value.sequence().value());
        result.put("evidenceHash", value.evidenceHash().value());
        result.put("failureClassification", value.failureClassification()
                .map(Enum::name).orElse(null));
        return result;
    }

    private CommandEvidenceReference decodeEvidence(Map<String, Object> value) {
        return new CommandEvidenceReference(
                new CommandEvidenceId(uuid(value, "id")),
                new EvidenceSequence(number(value, "sequence")),
                new TaskFactHash(text(value, "evidenceHash")),
                optionalText(value, "failureClassification")
                        .map(EvidenceFailureClassification::valueOf));
    }

    private Map<String, Object> encodeReviewer(ReviewerExecutionReference value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("agentProfileId", value.agentProfileId().toString());
        result.put("agentProfileVersion", value.agentProfileVersion());
        result.put("agentPrincipalId", value.agentPrincipalId().toString());
        result.put("reviewerOwnerMemberId", value.reviewerOwnerMemberId()
                .map(Object::toString).orElse(null));
        result.put("subjectOwnerMemberId", value.subjectOwnerMemberId()
                .map(Object::toString).orElse(null));
        result.put("relationship", value.relationship().name());
        result.put("templateKey", value.templateVersion().key().value());
        result.put("templateVersion", value.templateVersion().version());
        result.put("templateHash", value.templateHash().value());
        result.put("configurationRevision", value.configurationRevision().value());
        result.put("configurationHash", value.configurationHash().value());
        result.put("policySnapshotId", value.policySnapshotId().toString());
        result.put("policySnapshotRevision", value.policySnapshotRevision());
        result.put("policySnapshotHash", value.policySnapshotHash().value());
        return result;
    }

    private ReviewerExecutionReference decodeReviewer(
            Map<String, Object> value, ReviewSubject subject) {
        return new ReviewerExecutionReference(
                subject.scope(),
                subject.taskId(),
                subject.taskExecutionId(),
                new AgentProfileId(uuid(value, "agentProfileId")),
                number(value, "agentProfileVersion"),
                new PrincipalId(uuid(value, "agentPrincipalId")),
                optionalUuid(value, "reviewerOwnerMemberId").map(TeamMemberId::new),
                optionalUuid(value, "subjectOwnerMemberId").map(TeamMemberId::new),
                ReviewerRelationship.valueOf(text(value, "relationship")),
                AgentTemplateVersion.of(
                        text(value, "templateKey"), number(value, "templateVersion")),
                new AgentTemplateHash(text(value, "templateHash")),
                new AgentConfigurationRevision(number(value, "configurationRevision")),
                new AgentConfigurationHash(text(value, "configurationHash")),
                new PolicySnapshotId(uuid(value, "policySnapshotId")),
                number(value, "policySnapshotRevision"),
                new TaskFactHash(text(value, "policySnapshotHash")));
    }

    private static CodingTargetSnapshotReference codingTarget(Map<String, Object> value) {
        return new CodingTargetSnapshotReference(
                new CodingTargetSnapshotId(uuid(value, "codingTargetSnapshotId")),
                number(value, "codingTargetRevision"),
                new TaskFactHash(text(value, "codingTargetHash")));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(String value) {
        try {
            Object decoded = objectMapper.readValue(value, Map.class);
            return mapValue(decoded, "authoritySnapshot");
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Invalid Review authority snapshot JSON", exception);
        }
    }

    private static Map<String, Object> map(Map<String, Object> value, String key) {
        return mapValue(value.get(key), key);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapValue(Object value, String name) {
        if (!(value instanceof Map<?, ?> source)) {
            throw new IllegalStateException(name + " must be an object");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private static List<Map<String, Object>> listOfMaps(
            Map<String, Object> value, String key) {
        Object raw = value.get(key);
        if (!(raw instanceof List<?> source)) {
            throw new IllegalStateException(key + " must be an array");
        }
        return source.stream().map(item -> mapValue(item, key)).toList();
    }

    private static List<String> stringList(Map<String, Object> value, String key) {
        Object raw = value.get(key);
        if (!(raw instanceof List<?> source)) {
            throw new IllegalStateException(key + " must be an array");
        }
        return source.stream().map(item -> Objects.toString(item, null)).toList();
    }

    private static String text(Map<String, Object> value, String key) {
        Object raw = value.get(key);
        if (!(raw instanceof String result) || result.isBlank()) {
            throw new IllegalStateException(key + " must be non-blank text");
        }
        return result;
    }

    private static Optional<String> optionalText(Map<String, Object> value, String key) {
        Object raw = value.get(key);
        return raw == null ? Optional.empty() : Optional.of(text(value, key));
    }

    private static long number(Map<String, Object> value, String key) {
        Object raw = value.get(key);
        if (!(raw instanceof Number number)) {
            throw new IllegalStateException(key + " must be numeric");
        }
        return number.longValue();
    }

    private static int integer(Map<String, Object> value, String key) {
        return Math.toIntExact(number(value, key));
    }

    private static Optional<Integer> optionalInteger(Map<String, Object> value, String key) {
        return value.get(key) == null ? Optional.empty() : Optional.of(integer(value, key));
    }

    private static UUID uuid(Map<String, Object> value, String key) {
        return UUID.fromString(text(value, key));
    }

    private static Optional<UUID> optionalUuid(Map<String, Object> value, String key) {
        return optionalText(value, key).map(UUID::fromString);
    }

    record ContextAuthority(
            ReviewDiffReference diff,
            List<ReviewDiffHunk> hunks,
            ReviewTestEvidenceReference testEvidence,
            ReviewerExecutionReference reviewer) {}
}
