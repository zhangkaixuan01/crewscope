package io.crewscope.infrastructure.persistence.retrieval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.crewscope.application.retrieval.InjectionClaimedReferenceConflictException;
import io.crewscope.application.retrieval.InjectionClaimedReferences;
import io.crewscope.application.retrieval.InjectionFeedbackKind;
import io.crewscope.application.retrieval.InjectionReferenceFeedback;
import io.crewscope.application.retrieval.InjectionReferenceRepository;
import io.crewscope.domain.retrieval.ManifestSourceKey;
import io.crewscope.domain.retrieval.ManifestSourceType;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.TaskExecutionId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * JDBC home of the I02c evidence rows. Both writes derive their six scope columns
 * from the task_execution row itself (the V59/V60 composite-FK idiom), so no row can
 * ever disagree with its execution's tenant coordinates. Feedback inserts race with
 * ON CONFLICT DO NOTHING and replay the stored row on zero effect — the unique key
 * is the structural idempotency boundary. Claimed receipts translate the
 * (execution, attempt) unique violation into a read-back reconciliation: an
 * identical set replays, a different set surfaces as the conflict carrying the
 * stored receipt. The claimed column travels as JSONB through the same
 * SqlParameterValue idiom the manifest mapper uses.
 */
@Repository
public class JdbcInjectionReferenceRepositoryAdapter implements InjectionReferenceRepository {

    private static final String INSERT_FEEDBACK = """
            INSERT INTO crewscope.injection_reference_feedback (
                id, organization_id, team_id, workspace_id, project_id, task_id,
                execution_id, source_type, source_id, source_version, source_content_hash,
                member_principal_id, feedback_kind, created_at)
            SELECT ?, e.organization_id, e.team_id, e.workspace_id, e.project_id, e.task_id,
                   e.id, ?, ?, ?, ?, ?, ?, ?
            FROM crewscope.task_execution e
            WHERE e.id = ?
            ON CONFLICT (execution_id, source_type, source_id, source_version,
                         source_content_hash, member_principal_id)
            DO NOTHING
            """;

    private static final String FIND_FEEDBACK = """
            SELECT id, execution_id, source_type, source_id, source_version,
                   source_content_hash, member_principal_id, feedback_kind, created_at
            FROM crewscope.injection_reference_feedback
            WHERE organization_id = ? AND team_id = ? AND execution_id = ?
                  AND member_principal_id = ?
            ORDER BY created_at, id
            """;

    private static final String REPLAY_FEEDBACK = """
            SELECT id, execution_id, source_type, source_id, source_version,
                   source_content_hash, member_principal_id, feedback_kind, created_at
            FROM crewscope.injection_reference_feedback
            WHERE execution_id = ? AND source_type = ? AND source_id = ?
                  AND source_version = ? AND source_content_hash = ?
                  AND member_principal_id = ?
            """;

    private static final String INSERT_CLAIMED = """
            INSERT INTO crewscope.injection_claimed_reference (
                id, organization_id, team_id, workspace_id, project_id, task_id,
                execution_id, attempt, claimed, created_at)
            SELECT ?, e.organization_id, e.team_id, e.workspace_id, e.project_id, e.task_id,
                   e.id, ?, ?, ?
            FROM crewscope.task_execution e
            WHERE e.id = ?
            """;

    private static final String FIND_CLAIMED = """
            SELECT id, execution_id, attempt, claimed, created_at
            FROM crewscope.injection_claimed_reference
            WHERE organization_id = ? AND team_id = ? AND execution_id = ?
            ORDER BY attempt
            """;

    private static final String REPLAY_CLAIMED = """
            SELECT id, execution_id, attempt, claimed, created_at
            FROM crewscope.injection_claimed_reference
            WHERE execution_id = ? AND attempt = ?
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final RowMapper<InjectionReferenceFeedback> feedbackReader = new FeedbackReader();
    private final RowMapper<InjectionClaimedReferences> claimedReader = new ClaimedReader();

    public JdbcInjectionReferenceRepositoryAdapter(
            JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /**
     * Deliberately not one transaction: after the claimed INSERT trips a unique
     * violation, PostgreSQL has already aborted the surrounding transaction, so the
     * reconciliation read-back must run on its own connection. Each statement is
     * individually atomic and the unique keys make the whole sequence idempotent.
     */
    @Override
    public InjectionReferenceFeedback record(InjectionReferenceFeedback feedback) {
        Objects.requireNonNull(feedback, "feedback");
        int inserted = jdbc.update(INSERT_FEEDBACK, new Object[] {
            UUID.randomUUID(),
            feedback.source().type().name(),
            feedback.source().sourceId(),
            feedback.source().version(),
            feedback.source().contentHash(),
            feedback.memberPrincipalId().value(),
            feedback.kind().name(),
            Timestamp.from(feedback.createdAt().value()),
            feedback.executionId().value()});
        if (inserted > 0) {
            return feedback;
        }
        // Zero rows means either a replay against the unique key or a missing
        // execution (nothing for the SELECT to derive from) — the read-back decides.
        return replayFeedback(feedback);
    }

    @Override
    @Transactional(readOnly = true)
    public List<InjectionReferenceFeedback> findFeedback(
            OrganizationId organizationId,
            TeamId teamId,
            TaskExecutionId executionId,
            PrincipalId memberPrincipalId) {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(executionId, "executionId");
        Objects.requireNonNull(memberPrincipalId, "memberPrincipalId");
        return jdbc.query(
                FIND_FEEDBACK, feedbackReader,
                organizationId.value(), teamId.value(), executionId.value(),
                memberPrincipalId.value());
    }

    @Override
    public InjectionClaimedReferences recordClaimed(InjectionClaimedReferences receipt) {
        Objects.requireNonNull(receipt, "receipt");
        try {
            int inserted = jdbc.update(INSERT_CLAIMED, new Object[] {
                UUID.randomUUID(),
                receipt.attempt(),
                jsonb(keyRows(receipt.claimed())),
                Timestamp.from(receipt.createdAt().value()),
                receipt.executionId().value()});
            if (inserted > 0) {
                return receipt;
            }
            throw new AggregateNotFoundException("TaskExecution", receipt.executionId());
        } catch (DataIntegrityViolationException failure) {
            return reconcile(failure, receipt);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<InjectionClaimedReferences> findClaimed(
            OrganizationId organizationId,
            TeamId teamId,
            TaskExecutionId executionId) {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(executionId, "executionId");
        return jdbc.query(
                FIND_CLAIMED, claimedReader,
                organizationId.value(), teamId.value(), executionId.value());
    }

    // ------------------------------------------------------------------ reconciliation

    private InjectionReferenceFeedback replayFeedback(InjectionReferenceFeedback feedback) {
        List<InjectionReferenceFeedback> stored = jdbc.query(
                REPLAY_FEEDBACK, feedbackReader,
                feedback.executionId().value(),
                feedback.source().type().name(),
                feedback.source().sourceId(),
                feedback.source().version(),
                feedback.source().contentHash(),
                feedback.memberPrincipalId().value());
        if (stored.isEmpty()) {
            throw new AggregateNotFoundException("TaskExecution", feedback.executionId());
        }
        return stored.get(0);
    }

    private InjectionClaimedReferences reconcile(
            DataIntegrityViolationException failure, InjectionClaimedReferences receipt) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof java.sql.SQLException sql
                    && "23505".equals(sql.getSQLState())
                    && current.getMessage() != null
                    && current.getMessage().toLowerCase(Locale.ROOT)
                            .contains("uk_injection_claimed_reference_attempt")) {
                // The uk tripped, so a row exists — but read defensively: an empty
                // read-back (queryForObject would throw its own exception) must not
                // mask the integrity failure.
                List<InjectionClaimedReferences> stored = jdbc.query(
                        REPLAY_CLAIMED, claimedReader,
                        receipt.executionId().value(), receipt.attempt());
                if (stored.isEmpty()) {
                    throw failure;
                }
                if (stored.get(0).sameClaimAs(receipt)) {
                    return stored.get(0);
                }
                throw new InjectionClaimedReferenceConflictException(stored.get(0));
            }
        }
        throw failure;
    }

    // ------------------------------------------------------------------ readers

    private final class FeedbackReader implements RowMapper<InjectionReferenceFeedback> {
        @Override
        public InjectionReferenceFeedback mapRow(ResultSet row, int rowNumber)
                throws SQLException {
            return new InjectionReferenceFeedback(
                    new TaskExecutionId(row.getObject("execution_id", UUID.class)),
                    new ManifestSourceKey(
                            ManifestSourceType.valueOf(row.getString("source_type")),
                            row.getString("source_id"),
                            row.getLong("source_version"),
                            row.getString("source_content_hash")),
                    new PrincipalId(row.getObject("member_principal_id", UUID.class)),
                    InjectionFeedbackKind.valueOf(row.getString("feedback_kind")),
                    UtcTimestamp.from(
                            row.getObject("created_at", OffsetDateTime.class).toInstant()));
        }
    }

    private final class ClaimedReader implements RowMapper<InjectionClaimedReferences> {
        @Override
        public InjectionClaimedReferences mapRow(ResultSet row, int rowNumber)
                throws SQLException {
            try {
                return new InjectionClaimedReferences(
                        new TaskExecutionId(row.getObject("execution_id", UUID.class)),
                        row.getInt("attempt"),
                        keys(row.getString("claimed")),
                        UtcTimestamp.from(
                                row.getObject("created_at", OffsetDateTime.class).toInstant()));
            } catch (JsonProcessingException malformed) {
                throw new IllegalStateException(
                        "stored claimed-reference row failed to decode", malformed);
            }
        }
    }

    // ------------------------------------------------------------------ JSON codec

    private record KeyRow(
            String type, String sourceId, long version, String contentHash) {
    }

    private static List<KeyRow> keyRows(List<ManifestSourceKey> keys) {
        return keys.stream()
                .map(key -> new KeyRow(
                        key.type().name(), key.sourceId(), key.version(), key.contentHash()))
                .toList();
    }

    private List<ManifestSourceKey> keys(String json) throws JsonProcessingException {
        List<KeyRow> rows = objectMapper.readValue(json, new TypeReference<List<KeyRow>>() {});
        return rows.stream()
                .map(row -> new ManifestSourceKey(
                        ManifestSourceType.valueOf(row.type()),
                        row.sourceId(),
                        row.version(),
                        row.contentHash()))
                .toList();
    }

    private SqlParameterValue jsonb(Object value) {
        try {
            return new SqlParameterValue(
                    Types.OTHER, objectMapper.writeValueAsString(value));
        } catch (JsonProcessingException unreadable) {
            throw new IllegalStateException(
                    "claimed references failed to encode", unreadable);
        }
    }
}
