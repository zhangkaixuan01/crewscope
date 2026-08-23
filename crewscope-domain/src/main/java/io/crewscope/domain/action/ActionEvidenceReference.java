package io.crewscope.domain.action;

import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.ArtifactId;
import io.crewscope.domain.task.TaskFactHash;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/** Bounded evidence coordinate; raw Provider payloads and unlimited text stay in ArtifactStore. */
public record ActionEvidenceReference(
        String code, TaskFactHash evidenceHash, Optional<ArtifactId> artifactId) {

    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_.-]{0,127}");

    public ActionEvidenceReference {
        if (code == null || !CODE.matcher(code.strip()).matches()) {
            throw new DomainValidationException(
                    "actionEvidence.code", "must be a stable upper-case reason code");
        }
        code = code.strip();
        evidenceHash = Objects.requireNonNull(evidenceHash, "evidenceHash");
        artifactId = Objects.requireNonNull(artifactId, "artifactId");
    }

    public static ActionEvidenceReference hashed(String code, String canonicalEvidence) {
        return new ActionEvidenceReference(
                code,
                TaskFactHash.sha256(Objects.requireNonNull(canonicalEvidence, "canonicalEvidence")),
                Optional.empty());
    }
}
