package io.crewscope.application.review;

import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.domain.review.ContextPackage;
import io.crewscope.domain.review.ReviewCommentAnchor;
import io.crewscope.domain.review.ReviewCommentAnchorState;
import io.crewscope.domain.review.ReviewLineComment;
import io.crewscope.domain.review.ReviewRequest;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.review.ReviewCommentSide;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Authorized query and anchor reconciliation for Review line comments. */
public final class ReviewLineCommentQueryService {
    private final ReviewLineCommentRepository comments;
    private final ReviewRequestRepository requests;
    private final ContextPackageRepository contexts;
    private final WorkItemAccessPolicy accessPolicy;

    public ReviewLineCommentQueryService(
            ReviewLineCommentRepository comments,
            ReviewRequestRepository requests,
            ContextPackageRepository contexts,
            WorkItemAccessPolicy accessPolicy) {
        this.comments = Objects.requireNonNull(comments, "comments");
        this.requests = Objects.requireNonNull(requests, "requests");
        this.contexts = Objects.requireNonNull(contexts, "contexts");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
    }

    public ReviewLineCommentPage list(TeamAccessContext access, ReviewLineCommentQuery query) {
        ReviewRequest request = authorize(access, query.organizationId(), query.teamId(), query);
        List<ReviewLineComment> values = comments.findByRequest(
                query.organizationId(), request.id(), query.after().orElse(null), query.limit() + 1);
        Optional<String> next = values.size() > query.limit()
                ? Optional.of(values.get(query.limit() - 1).id().toString()) : Optional.empty();
        List<ReviewLineComment> page = values.size() > query.limit()
                ? values.subList(0, query.limit()) : values;
        ContextPackage context = contexts.findById(query.organizationId(), request.contextPackage().id())
                .orElseThrow(() -> new AggregateNotFoundException("ContextPackage", request.contextPackage().id()));
        return new ReviewLineCommentPage(page.stream().map(value -> reconcile(value, context)).toList(), next);
    }

    public ReviewLineComment get(TeamAccessContext access, OrganizationId organizationId,
            io.crewscope.domain.shared.id.TeamId teamId, io.crewscope.domain.task.TaskId taskId,
            io.crewscope.domain.task.TaskExecutionId executionId,
            io.crewscope.domain.review.ReviewRequestId requestId,
            io.crewscope.domain.review.ReviewLineCommentId commentId) {
        ReviewLineComment value = comments.findById(organizationId, commentId)
                .orElseThrow(() -> new AggregateNotFoundException("ReviewLineComment", commentId));
        ReviewLineCommentQuery query = new ReviewLineCommentQuery(organizationId, teamId, taskId, executionId, requestId, Optional.empty(), 1);
        ReviewRequest request = authorize(access, organizationId, teamId, query);
        if (!value.reviewRequestId().equals(request.id()) || !value.taskExecutionId().equals(executionId)) {
            throw new AggregateNotFoundException("ReviewLineComment", commentId);
        }
        ContextPackage context = contexts.findById(organizationId, request.contextPackage().id())
                .orElseThrow(() -> new AggregateNotFoundException("ContextPackage", request.contextPackage().id()));
        return reconcile(value, context);
    }

    private ReviewRequest authorize(TeamAccessContext access, OrganizationId organizationId,
            io.crewscope.domain.shared.id.TeamId teamId, ReviewLineCommentQuery query) {
        accessPolicy.requireVisibleTeam(access, organizationId, teamId);
        ReviewRequest request = requests.findById(organizationId, query.reviewRequestId())
                .orElseThrow(() -> new AggregateNotFoundException("ReviewRequest", query.reviewRequestId()));
        if (!request.scope().teamId().equals(teamId) || !request.taskId().equals(query.taskId())
                || !request.taskExecutionId().equals(query.executionId())) {
            throw new AggregateNotFoundException("ReviewRequest", query.reviewRequestId());
        }
        return request;
    }

    private static ReviewLineComment reconcile(ReviewLineComment value, ContextPackage context) {
        return value.withAnchorState(isCurrent(value.anchor(), context)
                ? ReviewCommentAnchorState.ACTIVE : ReviewCommentAnchorState.OUTDATED);
    }

    /** Hunk membership is authoritative; a generation change always invalidates the anchor. */
    static boolean isCurrent(ReviewCommentAnchor anchor, ContextPackage context) {
        if (!anchor.diffGeneration().equals(context.diff().generation())) return false;
        // The hunk range is expressed in NEW-file coordinates.  OLD comments must be
        // validated against the old range parsed from the unified-diff header instead of
        // reusing hunk.startLine/endLine.
        return context.hunks().stream().anyMatch(hunk -> hunk.path().equals(anchor.location().path())
                && lineHash(hunk, anchor).map(anchor.lineContentHash()::equals).orElse(false));
    }

    private static Optional<io.crewscope.domain.task.RuntimeContentHash> lineHash(
            io.crewscope.domain.review.ReviewDiffHunk hunk, ReviewCommentAnchor anchor) {
        if (hunk.patch().isEmpty()) return Optional.empty();
        String[] lines = hunk.patch().orElseThrow().split("\\n", -1);
        java.util.regex.Matcher header = java.util.regex.Pattern.compile("^@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@").matcher(lines[0]);
        if (!header.find()) return Optional.empty();
        if (!lines[0].equals(anchor.hunkHeader())) return Optional.empty();
        ReviewCommentSide side = anchor.side();
        int target = anchor.location().startLine();
        int oldLine = Integer.parseInt(header.group(1));
        int newLine = Integer.parseInt(header.group(2));
        for (int i = 1; i < lines.length; i++) {
            String value = lines[i];
            if (value.isEmpty() || value.charAt(0) == '\\') continue;
            char op = value.charAt(0);
            String content = value.substring(1);
            boolean matches = (side == ReviewCommentSide.OLD && (op == ' ' || op == '-') && oldLine == target)
                    || (side == ReviewCommentSide.NEW && (op == ' ' || op == '+') && newLine == target);
            if (matches) {
                return Optional.of(io.crewscope.domain.task.RuntimeContentHash.sha256(content));
            }
            if (op == ' ') { oldLine++; newLine++; }
            else if (op == '-') oldLine++;
            else if (op == '+') newLine++;
        }
        return Optional.empty();
    }
}
