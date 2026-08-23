package io.crewscope.domain.action;

import io.crewscope.domain.coding.RepositoryBranchName;
import io.crewscope.domain.coding.RepositoryCommitId;
import io.crewscope.domain.provider.ConnectionId;
import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.Objects;

/** Exact GitHub Draft PR business key and user-visible content. */
public record CreateDraftPullRequestActionParameters(
        ExternalRepositoryId repositoryId,
        RepositoryBranchName head,
        RepositoryBranchName base,
        RepositoryCommitId headSha,
        String title,
        String body,
        boolean draft,
        ConnectionId connectionId) implements ActionParameters {

    public static final int MAX_TITLE_LENGTH = 256;
    public static final int MAX_BODY_LENGTH = 65_536;

    public CreateDraftPullRequestActionParameters {
        repositoryId = Objects.requireNonNull(repositoryId, "repositoryId");
        head = Objects.requireNonNull(head, "head");
        base = Objects.requireNonNull(base, "base");
        if (head.equals(base)) {
            throw new DomainValidationException(
                    "plannedAction.head", "must differ from the Draft pull request base branch");
        }
        headSha = Objects.requireNonNull(headSha, "headSha");
        title = requireText(title, "plannedAction.title", MAX_TITLE_LENGTH);
        body = requireText(body, "plannedAction.body", MAX_BODY_LENGTH);
        if (!draft) {
            throw new DomainValidationException(
                    "plannedAction.draft", "M5 only permits creation of Draft pull requests");
        }
        connectionId = Objects.requireNonNull(connectionId, "connectionId");
    }

    @Override
    public ActionKind kind() {
        return ActionKind.CREATE_DRAFT_PR;
    }

    @Override
    public void appendCanonical(ActionCanonicalEncoder encoder) {
        encoder.add(repositoryId.value())
                .add(head.value())
                .add(base.value())
                .add(headSha.value())
                .add(title)
                .add(body)
                .add(Boolean.toString(draft))
                .add(connectionId.toString());
    }

    private static String requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.strip().length() > maxLength) {
            throw new DomainValidationException(field, "must be non-blank and within the size limit");
        }
        return value.strip();
    }
}
