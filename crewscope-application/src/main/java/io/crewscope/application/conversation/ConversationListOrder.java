package io.crewscope.application.conversation;

import io.crewscope.domain.conversation.Conversation;
import java.util.Comparator;
import java.util.Objects;

/** Canonical Conversation list order shared by repositories and cursor contract tests. */
public final class ConversationListOrder {

    public static final Comparator<Conversation> UPDATED_AT_DESC = ConversationListOrder::compare;

    private ConversationListOrder() {}

    /** Compares newer activity first and uses canonical UUID text as the stable tie-breaker. */
    public static int compare(Conversation left, Conversation right) {
        Conversation requiredLeft = Objects.requireNonNull(left, "left");
        Conversation requiredRight = Objects.requireNonNull(right, "right");
        int byUpdatedAt = requiredRight
                .audit()
                .updatedAt()
                .compareTo(requiredLeft.audit().updatedAt());
        if (byUpdatedAt != 0) {
            return byUpdatedAt;
        }
        return requiredRight.id().toString().compareTo(requiredLeft.id().toString());
    }

    static boolean isOlder(Conversation candidate, ConversationListCursor cursor) {
        Conversation requiredCandidate = Objects.requireNonNull(candidate, "candidate");
        ConversationListCursor requiredCursor = Objects.requireNonNull(cursor, "cursor");
        int byUpdatedAt = requiredCandidate
                .audit()
                .updatedAt()
                .compareTo(requiredCursor.updatedAt());
        if (byUpdatedAt != 0) {
            return byUpdatedAt < 0;
        }
        return requiredCandidate.id().toString().compareTo(requiredCursor.id().toString()) < 0;
    }
}
