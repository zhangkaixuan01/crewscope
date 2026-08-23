package io.crewscope.domain.review;

import io.crewscope.domain.task.TaskFactHash;
import java.util.Objects;

/** Exact immutable identity and content Hash of one Review subject. */
public record ReviewSubjectReference(
        ReviewSubjectId id, ReviewSubjectType type, TaskFactHash subjectHash) {

    public ReviewSubjectReference {
        id = Objects.requireNonNull(id, "id");
        type = Objects.requireNonNull(type, "type");
        subjectHash = Objects.requireNonNull(subjectHash, "subjectHash");
    }
}
