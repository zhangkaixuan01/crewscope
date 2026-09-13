package io.crewscope.application.review;

import java.util.Objects;

/** Command payload for editing or soft-deleting a user's own line comment. */
public record ResolveReviewLineCommentCommand(String content, long expectedVersion) {
    public ResolveReviewLineCommentCommand {
        if (content != null) {
            content = content.strip();
        }
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must be non-negative");
        }
    }

    public static ResolveReviewLineCommentCommand edit(String content, long expectedVersion) {
        return new ResolveReviewLineCommentCommand(Objects.requireNonNull(content, "content"), expectedVersion);
    }

    public static ResolveReviewLineCommentCommand delete(long expectedVersion) {
        return new ResolveReviewLineCommentCommand(null, expectedVersion);
    }

    public boolean deletion() {
        return content == null;
    }
}
