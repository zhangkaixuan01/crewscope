package io.crewscope.infrastructure.persistence.organization;

import io.crewscope.application.artifact.ArtifactDescriptor;
import io.crewscope.application.artifact.ArtifactStore;
import io.crewscope.application.organization.governance.ExportResourceFamily;
import io.crewscope.application.organization.governance.OrganizationExportResourceReader;
import io.crewscope.application.retrieval.KnowledgeEmbeddingVector;
import io.crewscope.application.retrieval.KnowledgeEmbeddingVectorStore;
import io.crewscope.application.retrieval.RepositoryChunkEmbeddingExportRow;
import io.crewscope.application.retrieval.RepositoryChunkVectorStore;
import io.crewscope.domain.shared.id.ArtifactId;
import io.crewscope.domain.shared.id.OrganizationId;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * JDBC + store walk implementation of the export reader (M12-A01, S01 freeze 3.4). Every
 * family is a bounded keyset page of stable JSON rows, and the continuation cursor rides the
 * page's own last row. Column whitelists are the security boundary: credential families
 * project references only (never ciphertext or password hashes), task credential grants project
 * coordinates only (never token or snapshot hashes), artifact references project descriptors
 * only (never blob content), and the two vector families encode geometry as base64
 * little-endian float32.
 */
@Repository
public class JdbcOrganizationExportResourceReader implements OrganizationExportResourceReader {

    /** Descriptor/vector walk page size for families not driven by one SQL LIMIT. */
    private static final int WALK_PAGE = 500;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectProvider<KnowledgeEmbeddingVectorStore> knowledgeVectors;
    private final ObjectProvider<RepositoryChunkVectorStore> chunkVectors;
    private final ArtifactStore artifacts;
    private final ObjectMapper objectMapper;

    public JdbcOrganizationExportResourceReader(
            NamedParameterJdbcTemplate jdbc,
            ObjectProvider<KnowledgeEmbeddingVectorStore> knowledgeVectors,
            ObjectProvider<RepositoryChunkVectorStore> chunkVectors,
            ArtifactStore artifacts,
            ObjectMapper objectMapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.knowledgeVectors = Objects.requireNonNull(knowledgeVectors, "knowledgeVectors");
        this.chunkVectors = Objects.requireNonNull(chunkVectors, "chunkVectors");
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public boolean vectorStoreEnabled() {
        // The vector beans exist only when the operator opted in (KnowledgeVectorConfiguration);
        // a non-opting deployment sees no bean at all, which is the same disabled chain.
        KnowledgeEmbeddingVectorStore knowledge = knowledgeVectors.getIfAvailable();
        RepositoryChunkVectorStore chunks = chunkVectors.getIfAvailable();
        return knowledge != null && chunks != null && knowledge.isEnabled() && chunks.isEnabled();
    }

    @Override
    @Transactional(readOnly = true)
    public long countFamily(OrganizationId organizationId, ExportResourceFamily family) {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(family, "family");
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("organizationId", organizationId.value());
        return switch (family) {
            case ORGANIZATION -> count("SELECT COUNT(*) FROM crewscope.organization"
                    + " WHERE id = :organizationId", parameters);
            case LOCAL_CREDENTIAL -> count("""
                    SELECT COUNT(*) FROM crewscope.local_credential
                     WHERE account_id IN (SELECT account_id FROM crewscope.account_organization_binding
                                          WHERE organization_id = :organizationId)
                    """, parameters);
            case USER_ACCOUNT -> count("""
                    SELECT COUNT(*) FROM crewscope.user_account
                     WHERE id IN (SELECT account_id FROM crewscope.account_organization_binding
                                  WHERE organization_id = :organizationId)
                    """, parameters);
            case ARTIFACT_REFERENCE -> countArtifactReferences(organizationId);
            case KNOWLEDGE_ENTRY_VERSION -> count("""
                    SELECT COUNT(*) FROM crewscope.knowledge_entry_version
                     WHERE organization_id = :organizationId
                    """, parameters);
            case REPOSITORY_INDEX_GENERATION -> count("""
                    SELECT COUNT(*) FROM crewscope.repository_index_generation
                     WHERE organization_id = :organizationId
                    """, parameters);
            case TASK_CREDENTIAL_GRANT -> count("""
                    SELECT COUNT(*) FROM crewscope.task_credential_grant
                     WHERE organization_id = :organizationId
                    """, parameters);
            case CREDENTIAL_SECRET -> count("""
                    SELECT COUNT(*) FROM crewscope.credential_secret
                     WHERE organization_id = :organizationId
                    """, parameters);
            case ACCOUNT_ORGANIZATION_BINDING -> count("""
                    SELECT COUNT(*) FROM crewscope.account_organization_binding
                     WHERE organization_id = :organizationId
                    """, parameters);
            // The vector tables live in the same schema but only exist when the operator opted
            // in; a disabled chain counts zero so the create-time item preview still freezes
            // (process later settles those items as SKIPPED/VECTOR_STORE_DISABLED).
            case KNOWLEDGE_ENTRY_EMBEDDING -> vectorStoreEnabled() ? count("""
                    SELECT COUNT(*) FROM crewscope.knowledge_entry_embedding
                     WHERE organization_id = :organizationId
                    """, parameters) : 0L;
            case REPOSITORY_CHUNK_EMBEDDING -> vectorStoreEnabled() ? count("""
                    SELECT COUNT(*) FROM crewscope.repository_chunk_embedding
                     WHERE organization_id = :organizationId
                    """, parameters) : 0L;
            default -> count(orgScopedCount(tableOf(family)), parameters);
        };
    }

    @Override
    @Transactional(readOnly = true)
    public ExportResourcePage readFamily(
            OrganizationId organizationId, ExportResourceFamily family, String afterCursor, int limit) {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(family, "family");
        if (limit <= 0) {
            return new ExportResourcePage(List.of(), null);
        }
        return switch (family) {
            case ORGANIZATION -> readSingleOrganization(organizationId);
            case KNOWLEDGE_ENTRY_VERSION -> readKnowledgeEntryVersions(
                    organizationId, afterCursor, limit);
            case REPOSITORY_INDEX_GENERATION -> readRepositoryGenerations(
                    organizationId, afterCursor, limit);
            case ARTIFACT_REFERENCE -> readArtifactReferences(organizationId, afterCursor, limit);
            case KNOWLEDGE_ENTRY_EMBEDDING -> readKnowledgeEmbeddings(
                    organizationId, afterCursor, limit);
            case REPOSITORY_CHUNK_EMBEDDING -> readChunkEmbeddings(
                    organizationId, afterCursor, limit);
            case TASK_CREDENTIAL_GRANT -> readWhitelisted(
                    organizationId, "crewscope.task_credential_grant",
                    """
                    id, organization_id, team_id, workspace_id, project_id, task_id,
                    task_execution_id, attempt, runtime_environment, runtime_id, worker_id,
                    execution_principal_id, issued_at, expires_at, status, use_count,
                    last_used_at, terminated_by_principal_id, terminated_at, termination_reason,
                    version, created_at, updated_at
                    """,
                    "organization_id = :organizationId", afterCursor, limit);
            case CREDENTIAL_SECRET -> readWhitelisted(
                    organizationId, "crewscope.credential_secret",
                    """
                    id, organization_id, team_id, principal_id, subject_type, subject_id,
                    credential_key, provider_key, connection_ref, credential_type, status,
                    expires_at, rotated_at, revoked_at, version, created_at, updated_at
                    """,
                    "organization_id = :organizationId", afterCursor, limit);
            case LOCAL_CREDENTIAL -> readWhitelisted(
                    organizationId, "crewscope.local_credential",
                    """
                    id, account_id, algorithm, credential_version, password_changed_at,
                    version, created_at, updated_at
                    """,
                    """
                    account_id IN (SELECT account_id FROM crewscope.account_organization_binding
                                   WHERE organization_id = :organizationId)
                    """,
                    afterCursor, limit);
            case USER_ACCOUNT -> readWhitelisted(
                    organizationId, "crewscope.user_account",
                    """
                    id, username, username_normalized, email, email_normalized, display_name,
                    status, platform_role, security_version, version, created_at, updated_at
                    """,
                    """
                    id IN (SELECT account_id FROM crewscope.account_organization_binding
                           WHERE organization_id = :organizationId)
                    """,
                    afterCursor, limit);
            case ACCOUNT_ORGANIZATION_BINDING -> readWhitelisted(
                    organizationId, "crewscope.account_organization_binding",
                    "id, account_id, organization_id, principal_id, status, version,"
                            + " created_at, updated_at",
                    "organization_id = :organizationId", afterCursor, limit);
            default -> readWhitelisted(
                    organizationId, tableOf(family), "*", "organization_id = :organizationId",
                    afterCursor, limit);
        };
    }

    /** Tables whose every column is exportable; the id (event_id) column drives the keyset. */
    private static String tableOf(ExportResourceFamily family) {
        return switch (family) {
            case TEAM -> "crewscope.team";
            case TEAM_MEMBER -> "crewscope.team_member";
            case TEAM_ROLE -> "crewscope.team_role";
            case TEAM_MEMBER_ROLE -> "crewscope.team_member_role";
            case WORKSPACE -> "crewscope.workspace";
            case WORK_PROJECT -> "crewscope.work_project";
            case WORK_ITEM -> "crewscope.work_item";
            case WORK_ITEM_RELATION -> "crewscope.work_item_relation";
            case TASK -> "crewscope.task";
            case TASK_EXECUTION -> "crewscope.task_execution";
            case RESPONSIBILITY_ASSIGNMENT -> "crewscope.responsibility_assignment";
            case KNOWLEDGE_ENTRY -> "crewscope.knowledge_entry";
            case AUDIT_EVENT -> "crewscope.audit_event";
            default -> throw new IllegalArgumentException(
                    "Family " + family + " is not a plain table family");
        };
    }

    private static String orgScopedCount(String table) {
        return "SELECT COUNT(*) FROM " + table + " WHERE organization_id = :organizationId";
    }

    private long count(String sql, MapSqlParameterSource parameters) {
        Long value = jdbc.queryForObject(sql, parameters, Long.class);
        return value == null ? 0L : value;
    }

    private ExportResourcePage readSingleOrganization(OrganizationId organizationId) {
        List<String> rows = jdbc.query(
                "SELECT row_to_json(t)::text FROM (SELECT * FROM crewscope.organization"
                        + " WHERE id = :organizationId) t",
                new MapSqlParameterSource("organizationId", organizationId.value()),
                (rs, rowNum) -> rs.getString(1));
        return rows.isEmpty()
                ? new ExportResourcePage(List.of(), null)
                : new ExportResourcePage(rows, null);
    }

    /**
     * One bounded id-keyset page. The query returns the cursor key next to each row JSON, so the
     * continuation cursor always rides the page's own last row — a full page promises more rows,
     * a short page ends the family.
     */
    private ExportResourcePage readWhitelisted(
            OrganizationId organizationId,
            String table,
            String columns,
            String scopePredicate,
            String afterCursor,
            int limit) {
        String key = table.equals("crewscope.audit_event") ? "event_id" : "id";
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("organizationId", organizationId.value())
                .addValue("limit", limit);
        if (afterCursor == null || afterCursor.isBlank()) {
            parameters.addValue("cursor", new java.util.UUID(0L, 0L));
        } else {
            parameters.addValue("cursor", java.util.UUID.fromString(afterCursor));
        }
        List<String> rows = new ArrayList<>();
        String[] lastKey = new String[1];
        jdbc.query(
                "SELECT " + key + "::text AS cursor_key, row_to_json(t)::text AS row_json"
                        + " FROM (SELECT " + columns + " FROM " + table
                        + " WHERE " + scopePredicate + " AND " + key + " > :cursor"
                        + " ORDER BY " + key + " LIMIT :limit) t",
                parameters,
                rs -> {
                    rows.add(rs.getString("row_json"));
                    lastKey[0] = rs.getString("cursor_key");
                });
        return rows.size() < limit
                ? new ExportResourcePage(rows, null)
                : new ExportResourcePage(rows, Objects.requireNonNull(lastKey[0]));
    }

    /** KNOWLEDGE_ENTRY_VERSION: composite keyset (entry_id, revision). */
    private ExportResourcePage readKnowledgeEntryVersions(
            OrganizationId organizationId, String afterCursor, int limit) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("organizationId", organizationId.value())
                .addValue("limit", limit);
        if (afterCursor != null && !afterCursor.isBlank()) {
            String[] cursor = afterCursor.split("\\|", 2);
            parameters.addValue("entryId", java.util.UUID.fromString(cursor[0]));
            parameters.addValue("revision", Long.parseLong(cursor[1]));
        }
        String predicate = afterCursor == null || afterCursor.isBlank()
                ? "TRUE"
                : "(entry_id, revision) > (CAST(:entryId AS uuid), CAST(:revision AS bigint))";
        List<String> rows = new ArrayList<>();
        String[] lastKey = new String[1];
        jdbc.query(
                "SELECT entry_id::text || '|' || revision AS cursor_key,"
                        + " row_to_json(t)::text AS row_json"
                        + " FROM (SELECT * FROM crewscope.knowledge_entry_version"
                        + " WHERE organization_id = :organizationId AND " + predicate
                        + " ORDER BY entry_id, revision LIMIT :limit) t",
                parameters,
                rs -> {
                    rows.add(rs.getString("row_json"));
                    lastKey[0] = rs.getString("cursor_key");
                });
        return rows.size() < limit
                ? new ExportResourcePage(rows, null)
                : new ExportResourcePage(rows, Objects.requireNonNull(lastKey[0]));
    }

    /** REPOSITORY_INDEX_GENERATION: composite keyset (index_key, build_sequence). */
    private ExportResourcePage readRepositoryGenerations(
            OrganizationId organizationId, String afterCursor, int limit) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("organizationId", organizationId.value())
                .addValue("limit", limit);
        if (afterCursor != null && !afterCursor.isBlank()) {
            String[] cursor = afterCursor.split("\\|", 2);
            parameters.addValue("indexKey", cursor[0]);
            parameters.addValue("buildSequence", Long.parseLong(cursor[1]));
        }
        String predicate = afterCursor == null || afterCursor.isBlank()
                ? "TRUE"
                : "(index_key, build_sequence) > (CAST(:indexKey AS char(64)),"
                        + " CAST(:buildSequence AS bigint))";
        List<String> rows = new ArrayList<>();
        String[] lastKey = new String[1];
        jdbc.query(
                "SELECT index_key || '|' || build_sequence AS cursor_key,"
                        + " row_to_json(t)::text AS row_json"
                        + " FROM (SELECT * FROM crewscope.repository_index_generation"
                        + " WHERE organization_id = :organizationId AND " + predicate
                        + " ORDER BY index_key, build_sequence LIMIT :limit) t",
                parameters,
                rs -> {
                    rows.add(rs.getString("row_json"));
                    lastKey[0] = rs.getString("cursor_key");
                });
        return rows.size() < limit
                ? new ExportResourcePage(rows, null)
                : new ExportResourcePage(rows, Objects.requireNonNull(lastKey[0]));
    }

    /** ARTIFACT_REFERENCE: descriptor walk — descriptors only, never blob content. */
    private ExportResourcePage readArtifactReferences(
            OrganizationId organizationId, String afterCursor, int limit) {
        List<String> rows = new ArrayList<>(limit);
        String cursor = afterCursor;
        while (rows.size() < limit) {
            int pageSize = Math.min(limit - rows.size(), WALK_PAGE);
            List<ArtifactDescriptor> page = artifacts.listDescriptorsByOrganization(
                    organizationId, Optional.ofNullable(cursor).map(ArtifactId::from), pageSize);
            for (ArtifactDescriptor descriptor : page) {
                if (rows.size() >= limit) {
                    break;
                }
                rows.add(objectMapper.writeValueAsString(descriptorRow(descriptor)));
            }
            if (page.size() < pageSize) {
                return new ExportResourcePage(rows, null);
            }
            cursor = page.get(page.size() - 1).artifactId().toString();
        }
        return new ExportResourcePage(rows, cursor);
    }

    /** KNOWLEDGE_ENTRY_EMBEDDING: vector store page, geometry as base64 little-endian float32. */
    private ExportResourcePage readKnowledgeEmbeddings(
            OrganizationId organizationId, String afterCursor, int limit) {
        List<KnowledgeEmbeddingVector> page =
                knowledgeVectors.getObject().pageByOrganization(organizationId, afterCursor, limit);
        List<String> rows = new ArrayList<>(page.size());
        for (KnowledgeEmbeddingVector vector : page) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("teamId", vector.teamId().value().toString());
            row.put("entryId", vector.entryId().value().toString());
            row.put("revision", vector.revision().value());
            row.put("modelKey", vector.model().modelKey());
            row.put("modelRevision", vector.model().revision());
            row.put("dimension", vector.model().dimension());
            row.put("contentHash", vector.contentHash());
            row.put("embedding", base64LeFloat32(vector.embedding()));
            rows.add(objectMapper.writeValueAsString(row));
        }
        if (page.size() < limit) {
            return new ExportResourcePage(rows, null);
        }
        KnowledgeEmbeddingVector tail = page.get(page.size() - 1);
        return new ExportResourcePage(rows, tail.entryId().value() + "|" + tail.revision().value()
                + "|" + tail.model().modelKey() + "|" + tail.model().dimension());
    }

    /** REPOSITORY_CHUNK_EMBEDDING: vector store page, geometry as base64 little-endian float32. */
    private ExportResourcePage readChunkEmbeddings(
            OrganizationId organizationId, String afterCursor, int limit) {
        List<RepositoryChunkEmbeddingExportRow> page =
                chunkVectors.getObject().pageByOrganization(organizationId, afterCursor, limit);
        List<String> rows = new ArrayList<>(page.size());
        for (RepositoryChunkEmbeddingExportRow vector : page) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("indexKey", vector.indexKey());
            row.put("buildSequence", vector.buildSequence());
            row.put("chunkSeq", vector.chunkSeq());
            row.put("teamId", vector.teamId().value().toString());
            row.put("repositoryBindingId", vector.repositoryBindingId().value().toString());
            row.put("path", vector.path());
            row.put("language", vector.language());
            row.put("startLine", vector.startLine());
            row.put("endLine", vector.endLine());
            row.put("contentHash", vector.contentHash());
            row.put("modelKey", vector.modelKey());
            row.put("modelRevision", vector.modelRevision());
            row.put("dimension", vector.dimension());
            row.put("embedding", base64LeFloat32(vector.embedding()));
            rows.add(objectMapper.writeValueAsString(row));
        }
        if (page.size() < limit) {
            return new ExportResourcePage(rows, null);
        }
        RepositoryChunkEmbeddingExportRow tail = page.get(page.size() - 1);
        return new ExportResourcePage(
                rows, tail.indexKey() + "|" + tail.buildSequence() + "|" + tail.chunkSeq());
    }

    private long countArtifactReferences(OrganizationId organizationId) {
        long total = 0;
        String cursor = null;
        while (true) {
            List<ArtifactDescriptor> page = artifacts.listDescriptorsByOrganization(
                    organizationId, Optional.ofNullable(cursor).map(ArtifactId::from), WALK_PAGE);
            total += page.size();
            if (page.size() < WALK_PAGE) {
                return total;
            }
            cursor = page.get(page.size() - 1).artifactId().toString();
        }
    }

    private Map<String, Object> descriptorRow(ArtifactDescriptor descriptor) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("artifactId", descriptor.artifactId().toString());
        row.put("organizationId", descriptor.scope().organizationId().value().toString());
        row.put("teamId", descriptor.scope().teamId().map(Object::toString).orElse(null));
        row.put("workspaceId", descriptor.scope().workspaceId().map(Object::toString).orElse(null));
        row.put("contentType", descriptor.contentType());
        row.put("size", descriptor.size());
        row.put("sha256", descriptor.sha256().toString());
        row.put("dataClassification", descriptor.dataClassification().name());
        row.put("visibility", descriptor.visibility().name());
        row.put("storageUri", descriptor.storageUri().toString());
        row.put("encryption", descriptor.encryption().toString());
        row.put("producerPrincipalId", descriptor.producer().principalId().value().toString());
        row.put("createdAt", descriptor.createdAt().toString());
        row.put("retentionUntil", descriptor.retentionUntil().map(Object::toString).orElse(null));
        row.put("tombstonedAt",
                descriptor.tombstone().map(value -> value.tombstonedAt().toString()).orElse(null));
        return row;
    }

    private static String base64LeFloat32(float[] embedding) {
        ByteBuffer buffer = ByteBuffer.allocate(embedding.length * 4)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (float component : embedding) {
            buffer.putFloat(component);
        }
        return Base64.getEncoder().encodeToString(buffer.array());
    }
}
