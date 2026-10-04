package io.crewscope.infrastructure.persistence.retrieval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.crewscope.application.retrieval.InjectionManifestConflictException;
import io.crewscope.application.retrieval.InjectionManifestRepository;
import io.crewscope.domain.retrieval.DegradationReasonCode;
import io.crewscope.domain.retrieval.InjectionManifest;
import io.crewscope.domain.retrieval.InjectionManifestId;
import io.crewscope.domain.retrieval.ManifestSourceRef;
import io.crewscope.domain.retrieval.ManifestSourceStage;
import io.crewscope.domain.retrieval.ManifestSourceType;
import io.crewscope.domain.retrieval.PromptBudgetSnapshot;
import io.crewscope.domain.retrieval.TrimRecord;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.TaskExecutionId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * JDBC home of sealed injection manifests (M10-I02b). The insert derives the six scope
 * columns from the task_execution row itself, so a manifest can never disagree with its
 * execution's tenant coordinates; the composite FK and the (execution_id, attempt)
 * unique key are the durability side of the idempotency contract — a racing append
 * surfaces as a unique violation translated into the conflict exception callers
 * converge with. The four evidence columns travel as JSONB through the same
 * SqlParameterValue idiom the agent-persistence mappers use.
 */
@Repository
public class JdbcInjectionManifestRepositoryAdapter implements InjectionManifestRepository {

    private static final String INSERT = """
            INSERT INTO crewscope.injection_manifest (
                id, organization_id, team_id, workspace_id, project_id, task_id,
                execution_id, attempt, source_references, trims, budget, degradations,
                created_at)
            SELECT ?, e.organization_id, e.team_id, e.workspace_id, e.project_id, e.task_id,
                   e.id, ?, ?, ?, ?, ?, ?
            FROM crewscope.task_execution e
            WHERE e.id = ?
            """;

    private static final String FIND_BY_ATTEMPT = """
            SELECT id, execution_id, attempt, source_references, trims, budget,
                   degradations, created_at
            FROM crewscope.injection_manifest
            WHERE organization_id = ? AND team_id = ? AND execution_id = ? AND attempt = ?
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final RowMapper<InjectionManifest> reader = new Reader();

    public JdbcInjectionManifestRepositoryAdapter(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    @Transactional
    public InjectionManifest append(InjectionManifest manifest) {
        Objects.requireNonNull(manifest, "manifest");
        try {
            int inserted = jdbc.update(INSERT, statementParameters(manifest));
            if (inserted == 0) {
                throw new AggregateNotFoundException("TaskExecution", manifest.executionId());
            }
            return manifest;
        } catch (DataIntegrityViolationException failure) {
            throw conflict(failure, manifest);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<InjectionManifest> findByAttempt(
            OrganizationId organizationId,
            TeamId teamId,
            TaskExecutionId executionId,
            int attempt) {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(executionId, "executionId");
        return jdbc.query(
                        FIND_BY_ATTEMPT, reader,
                        organizationId.value(), teamId.value(), executionId.value(), attempt)
                .stream()
                .findFirst();
    }

    // ------------------------------------------------------------------ statements

    private Object[] statementParameters(InjectionManifest manifest) {
        return new Object[] {
            manifest.id().value(),
            manifest.attempt(),
            jsonb(referenceRows(manifest.references())),
            jsonb(trimRows(manifest.trims())),
            jsonb(budgetRow(manifest.budget())),
            jsonb(degradationNames(manifest.degradations())),
            Timestamp.from(manifest.createdAt().value()),
            manifest.executionId().value()
        };
    }

    private final class Reader implements RowMapper<InjectionManifest> {
        @Override
        public InjectionManifest mapRow(ResultSet row, int rowNumber) throws SQLException {
            try {
                return new InjectionManifest(
                        new InjectionManifestId(row.getObject("id", UUID.class)),
                        new TaskExecutionId(row.getObject("execution_id", UUID.class)),
                        row.getInt("attempt"),
                        references(row.getString("source_references")),
                        trims(row.getString("trims")),
                        budget(row.getString("budget")),
                        degradations(row.getString("degradations")),
                        UtcTimestamp.from(
                                row.getObject("created_at", OffsetDateTime.class).toInstant()));
            } catch (JsonProcessingException malformed) {
                throw new IllegalStateException(
                        "stored injection manifest row failed to decode", malformed);
            }
        }
    }

    // ------------------------------------------------------------------ conflict translation

    private static RuntimeException conflict(
            DataIntegrityViolationException failure, InjectionManifest manifest) {
        String expected = "uk_injection_manifest_attempt";
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof java.sql.SQLException sql
                    && "23505".equals(sql.getSQLState())
                    && current.getMessage() != null
                    && current.getMessage().toLowerCase(Locale.ROOT).contains(expected)) {
                return new InjectionManifestConflictException(
                        manifest.executionId(), manifest.attempt());
            }
        }
        return failure;
    }

    // ------------------------------------------------------------------ JSON codec

    private record SourceRefRow(
            String type, String sourceId, long version, String contentHash, String stage) {
    }

    private record TrimRow(String layer, int trimmedCount, String reason) {
    }

    private record BudgetRow(
            long totalTokens, long knowledgeTokens, long chunkTokens, long memoryTokens) {
    }

    private static List<SourceRefRow> referenceRows(List<ManifestSourceRef> references) {
        return references.stream()
                .map(reference -> new SourceRefRow(
                        reference.type().name(), reference.sourceId(), reference.version(),
                        reference.contentHash(), reference.stage().name()))
                .toList();
    }

    private static List<TrimRow> trimRows(List<TrimRecord> trims) {
        return trims.stream()
                .map(trim -> new TrimRow(
                        trim.layer().name(), trim.trimmedCount(), trim.reason()))
                .toList();
    }

    private static BudgetRow budgetRow(PromptBudgetSnapshot budget) {
        return new BudgetRow(budget.totalTokens(), budget.knowledgeTokens(),
                budget.chunkTokens(), budget.memoryTokens());
    }

    private static List<String> degradationNames(List<DegradationReasonCode> degradations) {
        return degradations.stream().map(Enum::name).toList();
    }

    private List<ManifestSourceRef> references(String json) throws JsonProcessingException {
        List<SourceRefRow> rows =
                objectMapper.readValue(json, new TypeReference<List<SourceRefRow>>() {});
        return rows.stream()
                .map(row -> new ManifestSourceRef(
                        ManifestSourceType.valueOf(row.type()),
                        row.sourceId(),
                        row.version(),
                        row.contentHash(),
                        ManifestSourceStage.valueOf(row.stage())))
                .toList();
    }

    private List<TrimRecord> trims(String json) throws JsonProcessingException {
        List<TrimRow> rows = objectMapper.readValue(json, new TypeReference<List<TrimRow>>() {});
        return rows.stream()
                .map(row -> new TrimRecord(
                        ManifestSourceType.valueOf(row.layer()),
                        row.trimmedCount(),
                        row.reason()))
                .toList();
    }

    private PromptBudgetSnapshot budget(String json) throws JsonProcessingException {
        BudgetRow row = objectMapper.readValue(json, BudgetRow.class);
        return new PromptBudgetSnapshot(
                row.totalTokens(), row.knowledgeTokens(), row.chunkTokens(),
                row.memoryTokens());
    }

    private List<DegradationReasonCode> degradations(String json)
            throws JsonProcessingException {
        List<String> names =
                objectMapper.readValue(json, new TypeReference<List<String>>() {});
        return names.stream().map(DegradationReasonCode::valueOf).toList();
    }

    private SqlParameterValue jsonb(Object value) {
        try {
            return new SqlParameterValue(
                    Types.OTHER, objectMapper.writeValueAsString(value));
        } catch (JsonProcessingException unreadable) {
            throw new IllegalStateException(
                    "injection manifest evidence failed to encode", unreadable);
        }
    }
}
