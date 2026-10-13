package io.crewscope.infrastructure.persistence.organization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.crewscope.application.artifact.ArtifactStore;
import io.crewscope.application.organization.governance.AccountDeletionStepExecutor;
import io.crewscope.application.organization.governance.AuditRetentionGateway;
import io.crewscope.application.organization.governance.DataExportArtifact;
import io.crewscope.application.organization.governance.DeletionStepKey;
import io.crewscope.application.organization.governance.ExportResourceFamily;
import io.crewscope.application.organization.governance.GovernanceAuditRecord;
import io.crewscope.application.organization.governance.GovernanceAuditRecorder;
import io.crewscope.application.organization.governance.OrganizationAccountDeletionJobRepository;
import io.crewscope.application.organization.governance.OrganizationDataExportJobRepository;
import io.crewscope.application.organization.governance.OrganizationExportResourceReader;
import io.crewscope.application.retrieval.KnowledgeEmbeddingVectorStore;
import io.crewscope.application.retrieval.RepositoryChunkVectorStore;
import io.crewscope.domain.audit.AuditOutcome;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.identity.UserAccountId;
import io.crewscope.domain.organization.governance.AccountDeletionItem;
import io.crewscope.domain.organization.governance.AccountDeletionItemId;
import io.crewscope.domain.organization.governance.AccountDeletionJob;
import io.crewscope.domain.organization.governance.AccountDeletionJobId;
import io.crewscope.domain.organization.governance.DataExportItem;
import io.crewscope.domain.organization.governance.DataExportItemId;
import io.crewscope.domain.organization.governance.DataExportJob;
import io.crewscope.domain.organization.governance.DataExportJobId;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.infrastructure.persistence.audit.JdbcAuditRetentionGateway;
import io.crewscope.infrastructure.persistence.audit.JdbcGovernanceAuditRecorder;
import io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest;
import io.crewscope.infrastructure.transaction.SpringTransactionExecutor;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * PostgreSQL contract for the V69 governance tables: the optimistic-lock predicates of both job
 * adapters, the export reader's column whitelists (credential families project references only),
 * keyset pagination, one real step-executor SQL path, and the audit retention gateway's
 * SECURITY exception with its atomic self-evidence.
 */
@SpringBootTest(
        classes = OrganizationGovernancePersistenceM12A01IntegrationTest.TestApplication.class,
        properties = {
            "spring.flyway.schemas=crewscope",
            "spring.flyway.default-schema=crewscope",
            "spring.flyway.create-schemas=true",
            "spring.jpa.hibernate.ddl-auto=none",
            "spring.jpa.open-in-view=false"
        })
class OrganizationGovernancePersistenceM12A01IntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    private static final UtcTimestamp NOW =
            UtcTimestamp.from(Instant.parse("2026-10-12T12:00:00Z"));
    private static final String CHECKSUM_64 = "a".repeat(64);

    @Autowired private OrganizationDataExportJobRepository exportJobs;
    @Autowired private OrganizationAccountDeletionJobRepository deletionJobs;
    @Autowired private OrganizationExportResourceReader reader;
    @Autowired private GovernanceAuditRecorder governanceAudit;
    @Autowired private AuditRetentionGateway retentionGateway;
    @Autowired private AccountDeletionStepExecutor steps;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper objectMapper;

    private OrganizationId organizationId;
    private PrincipalId actorId;

    @BeforeEach
    void resetBusinessData() {
        // user_account/local_credential hold no organization column, so the organization
        // cascade alone would leak rows across tests; the identity tables are listed directly.
        jdbc.execute(
                "TRUNCATE TABLE crewscope.organization, crewscope.user_account,"
                        + " crewscope.credential_secret, crewscope.audit_event CASCADE");
        organizationId = OrganizationId.generate();
        actorId = PrincipalId.generate();
        jdbc.update(
                "INSERT INTO crewscope.organization (id, name, status) VALUES (?, 'Org', 'ACTIVE')",
                organizationId.value());
        jdbc.update(
                "INSERT INTO crewscope.principal (id, organization_id, principal_type,"
                        + " display_name, status) VALUES (?, ?, 'USER', 'Governor', 'ACTIVE')",
                actorId.value(), organizationId.value());
    }

    @Test
    void exportJobsRoundTripUnderOptimisticLocking() {
        DataExportJobId jobId = DataExportJobId.generate();
        DataExportJob job = DataExportJob.create(
                jobId, organizationId, actorId, UUID.randomUUID().toString(), false, NOW);
        DataExportItemId itemId = DataExportItemId.generate();
        DataExportItem item = DataExportItem.create(
                itemId, jobId, organizationId, "TEAM", 0, Optional.empty(), 1, actorId, NOW);
        exportJobs.createJob(job, List.of(item));

        DataExportJob stored = exportJobs.findJobById(organizationId, jobId).orElseThrow();
        assertEquals(0L, stored.version());
        assertEquals(1, exportJobs.findItems(organizationId, jobId).size());
        assertEquals(
                stored.id(),
                exportJobs.findJobByCommandId(organizationId, job.commandId())
                        .orElseThrow()
                        .id());

        DataExportJob running = exportJobs.updateJob(stored.markRunning(actorId, NOW));
        assertEquals(1L, running.version());
        assertThrows(
                OptimisticLockConflictException.class,
                () -> exportJobs.updateJob(stored.markRunning(actorId, NOW)));

        DataExportItem locked = exportJobs.lockItemById(organizationId, itemId).orElseThrow();
        DataExportItem done =
                exportJobs.updateItem(locked.markDone(CHECKSUM_64, actorId, NOW));
        assertEquals(1L, done.version());
        assertThrows(
                OptimisticLockConflictException.class,
                () -> exportJobs.updateItem(locked.markDone("b".repeat(64), actorId, NOW)));
    }

    @Test
    void exportArtifactsPersistAsByteStableTextRows() {
        DataExportJobId jobId = DataExportJobId.generate();
        DataExportJob job = DataExportJob.create(
                jobId, organizationId, actorId, UUID.randomUUID().toString(), true, NOW);
        DataExportItem item = DataExportItem.create(
                DataExportItemId.generate(), jobId, organizationId, "TEAM", 0,
                Optional.empty(), 2, actorId, NOW);
        exportJobs.createJob(job, List.of(item));
        String content = "{\"row\":1}\n{\"row\":2}\n";

        exportJobs.createArtifact(new DataExportArtifact(
                UUID.randomUUID(), organizationId, jobId, item.id(), 0,
                content, content.getBytes(StandardCharsets.UTF_8).length, CHECKSUM_64,
                actorId, NOW));

        List<DataExportArtifact> stored = exportJobs.findArtifacts(organizationId, jobId);
        assertEquals(1, stored.size());
        // TEXT, never JSONB: the checksum pins these exact bytes back to the item.
        assertEquals(content, stored.get(0).content());
        assertEquals(content.getBytes(StandardCharsets.UTF_8).length,
                stored.get(0).byteSize());
        assertEquals(CHECKSUM_64, stored.get(0).checksum());
        assertEquals(item.id(), stored.get(0).itemId());
    }

    @Test
    void deletionJobsMirrorTheSameOptimisticContract() {
        AccountDeletionJobId jobId = AccountDeletionJobId.generate();
        UserAccountId accountId = UserAccountId.generate();
        AccountDeletionItem pinned = AccountDeletionItem.create(
                AccountDeletionItemId.generate(), jobId, organizationId,
                DeletionStepKey.TOMBSTONE_IDENTITY.name(), "IDENTITY", 3, actorId, NOW);
        AccountDeletionJob job = AccountDeletionJob.create(
                jobId,
                organizationId,
                accountId,
                actorId,
                "offboarding after the audit finding closed",
                "0".repeat(64),
                UUID.randomUUID().toString(),
                "{\"inFlightTaskExecutions\":0,\"checkedAt\":\"" + NOW + "\"}",
                DeletionStepKey.BACKUP_RETENTION_STATEMENT,
                actorId,
                NOW);
        deletionJobs.createJob(job, List.of(pinned));
        assertEquals(
                DeletionStepKey.TOMBSTONE_IDENTITY.name(),
                deletionJobs.findItems(organizationId, jobId).get(0).stepKey());

        AccountDeletionJob stored = deletionJobs.findJobById(organizationId, jobId).orElseThrow();
        AccountDeletionJob confirmed =
                deletionJobs.updateJob(stored.markConfirmed(actorId, NOW));
        assertEquals(1L, confirmed.version());
        assertThrows(
                OptimisticLockConflictException.class,
                () -> deletionJobs.updateJob(stored.markConfirmed(actorId, NOW)));
    }

    @Test
    void credentialFamiliesProjectReferencesOnly() {
        UUID accountId = insertUserAccountWithLocalCredential();
        jdbc.update(
                """
                INSERT INTO crewscope.credential_secret (
                    id, organization_id, subject_type, subject_id, credential_key, provider_key,
                    credential_type, ciphertext, nonce, authentication_tag, key_id, algorithm,
                    aad_version, status)
                VALUES (?, ?, 'ORGANIZATION', ?, 'key-1', 'deepseek', 'OAUTH_REFRESH_TOKEN',
                        ?, ?, ?, 'kms-1', 'AES-256-GCM', 'v1', 'ACTIVE')
                """,
                UUID.randomUUID(), organizationId.value(), organizationId.value(),
                "ciphertext-bytes".getBytes(StandardCharsets.UTF_8),
                new byte[12], new byte[16]);

        Map<String, Object> secretRow = singleRow(
                reader.readFamily(organizationId, ExportResourceFamily.CREDENTIAL_SECRET,
                        null, 10));
        assertFalse(secretRow.containsKey("ciphertext"));
        assertFalse(secretRow.containsKey("nonce"));
        assertFalse(secretRow.containsKey("authentication_tag"));
        assertFalse(secretRow.containsKey("key_id"));
        assertFalse(secretRow.containsKey("algorithm"));
        assertFalse(secretRow.containsKey("aad_version"));
        assertTrue(secretRow.containsKey("credential_key"));
        assertTrue(secretRow.containsKey("status"));

        Map<String, Object> localRow = singleRow(
                reader.readFamily(organizationId, ExportResourceFamily.LOCAL_CREDENTIAL,
                        null, 10));
        assertFalse(localRow.containsKey("password_hash"));
        assertEquals(accountId.toString(), localRow.get("account_id"));

        Map<String, Object> accountRow = singleRow(
                reader.readFamily(organizationId, ExportResourceFamily.USER_ACCOUNT,
                        null, 10));
        assertFalse(accountRow.containsKey("password_hash"));
        assertEquals("echo-user", accountRow.get("username"));
    }

    @Test
    void plainFamiliesPageByKeysetAcrossBoundedWindows() {
        for (int i = 0; i < 3; i++) {
            insertAuditEvent(UUID.randomUUID(), "TEAM_CREATED", "TEAM",
                    Instant.parse("2026-10-01T12:00:0" + i + "Z"));
        }

        OrganizationExportResourceReader.ExportResourcePage first =
                reader.readFamily(organizationId, ExportResourceFamily.AUDIT_EVENT, null, 2);
        assertEquals(2, first.rows().size());
        assertTrue(first.hasNext());

        OrganizationExportResourceReader.ExportResourcePage second =
                reader.readFamily(
                        organizationId, ExportResourceFamily.AUDIT_EVENT,
                        first.nextCursor(), 2);
        assertEquals(1, second.rows().size());
        assertFalse(second.hasNext());
    }

    @Test
    void stepExecutorRevokesSubjectCredentialsIdempotently() {
        UUID accountId = insertUserAccountWithLocalCredential();
        jdbc.update(
                """
                INSERT INTO crewscope.credential_secret (
                    id, organization_id, principal_id, subject_type, subject_id,
                    credential_key, provider_key, credential_type, ciphertext, nonce,
                    authentication_tag, key_id, algorithm, aad_version, status)
                VALUES (?, ?, ?, 'PRINCIPAL', ?, 'key-step', 'deepseek', 'OAUTH_REFRESH_TOKEN',
                        ?, ?, ?, 'kms-1', 'AES-256-GCM', 'v1', 'ACTIVE')
                """,
                UUID.randomUUID(), organizationId.value(), actorId.value(), actorId.value(),
                "ciphertext-bytes".getBytes(StandardCharsets.UTF_8),
                new byte[12], new byte[16]);

        assertEquals(
                1L,
                steps.countStep(
                        organizationId, actorId,
                        DeletionStepKey.REVOKE_SUBJECT_CREDENTIALS, "CREDENTIALS"));
        assertEquals(
                1,
                steps.executeStep(
                        organizationId, actorId,
                        DeletionStepKey.REVOKE_SUBJECT_CREDENTIALS, "CREDENTIALS"));
        assertEquals(
                0L,
                steps.countStep(
                        organizationId, actorId,
                        DeletionStepKey.REVOKE_SUBJECT_CREDENTIALS, "CREDENTIALS"));
        // The V31 credential-history guard keeps the local password row: revocation is the
        // credential_secret status flip, the login surfaces die with TOMBSTONE_IDENTITY.
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM crewscope.local_credential WHERE account_id = ?",
                        Integer.class, accountId));
        assertEquals(
                "REVOKED",
                jdbc.queryForObject(
                        "SELECT status FROM crewscope.credential_secret"
                                + " WHERE principal_id = ?",
                        String.class, actorId.value()));
    }

    @Test
    void retentionGatewaySparesSecurityRowsAndSelfEvidencesAtomically() {
        UUID oldOperational = UUID.randomUUID();
        UUID oldSecurity = UUID.randomUUID();
        insertAuditEvent(oldOperational, "TEAM_CREATED", "TEAM",
                Instant.parse("2026-01-01T00:00:00Z"));
        insertAuditEvent(oldSecurity, "ORG_EXPORT_DOWNLOADED", "SECURITY",
                Instant.parse("2026-01-01T00:00:00Z"));
        insertAuditEvent(UUID.randomUUID(), "TEAM_CREATED", "TEAM",
                Instant.parse("2026-10-12T00:00:00Z"));

        long deleted = retentionGateway.deleteExpiredBefore(
                organizationId, AuditRetentionGateway.cutoffAt(NOW, Duration.ofDays(30)), 500);

        assertEquals(1L, deleted);
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM crewscope.audit_event WHERE event_id = ?",
                        Integer.class, oldOperational));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM crewscope.audit_event WHERE event_id = ?",
                        Integer.class, oldSecurity));
        // The sweep fact is written in the same transaction as the deletion itself.
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM crewscope.audit_event"
                                + " WHERE event_type = 'ORG_RETENTION_SWEEP_COMPLETED'",
                        Integer.class));
    }

    @Test
    void governanceAuditRecorderWritesSecurityCategoryRows() {
        Principal actor = Principal.create(
                actorId,
                PrincipalScope.organization(organizationId),
                PrincipalType.USER,
                Optional.empty(),
                "Governor",
                Optional.empty(),
                PrincipalVisibility.ORGANIZATION,
                NOW);
        governanceAudit.record(new GovernanceAuditRecord(
                GovernanceAuditRecord.Operation.EXPORT_DOWNLOAD,
                organizationId,
                actor,
                UUID.randomUUID(),
                AuditOutcome.SUCCEEDED,
                "job-1",
                2,
                null,
                NOW));

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT event_type, event_category, subject_type, outcome, retention_level"
                        + " FROM crewscope.audit_event WHERE event_type = 'ORG_EXPORT_DOWNLOADED'");
        assertEquals("SECURITY", row.get("event_category"));
        assertEquals("EXTENDED", row.get("retention_level"));
        assertEquals("ORGANIZATION", row.get("subject_type"));
        assertEquals("SUCCEEDED", row.get("outcome"));
    }

    /** Inserts one ACTIVE account with a binding to this test's principal and a local hash. */
    private UUID insertUserAccountWithLocalCredential() {
        UUID accountId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO crewscope.user_account (
                    id, username, username_normalized, email, email_normalized, display_name)
                VALUES (?, 'echo-user', 'echo-user', 'echo@example.com',
                        'echo@example.com', 'Echo')
                """,
                accountId);
        jdbc.update(
                """
                INSERT INTO crewscope.account_organization_binding (
                    id, account_id, organization_id, principal_id, status)
                VALUES (?, ?, ?, ?, 'ACTIVE')
                """,
                UUID.randomUUID(), accountId, organizationId.value(), actorId.value());
        jdbc.update(
                """
                INSERT INTO crewscope.local_credential (
                    id, account_id, password_hash, algorithm, password_changed_at,
                    created_at, updated_at)
                VALUES (?, ?, '{argon2id}v=19$m=65536,t=3,p=4$c2FsdHNhbHQ', 'argon2id',
                        ?, ?, ?)
                """,
                UUID.randomUUID(), accountId, NOW.toOffsetDateTime(),
                NOW.toOffsetDateTime(), NOW.toOffsetDateTime());
        return accountId;
    }

    private void insertAuditEvent(
            UUID eventId, String eventType, String category, Instant occurredAt) {
        jdbc.update(
                """
                INSERT INTO crewscope.audit_event (
                    event_id, organization_id, actor_type, event_type, event_category,
                    subject_type, outcome, correlation_id, schema_version, occurred_at, payload)
                VALUES (?, ?, 'USER', ?, ?, 'TEAM', 'SUCCEEDED', ?, '1', ?,
                        CAST('{}' AS JSONB))
                """,
                eventId, organizationId.value(), eventType, category,
                UUID.randomUUID(), occurredAt.atOffset(ZoneOffset.UTC));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> singleRow(
            OrganizationExportResourceReader.ExportResourcePage page) {
        assertEquals(1, page.rows().size());
        return objectMapper.readValue(page.rows().get(0), Map.class);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({
        JdbcOrganizationDataExportJobRepositoryAdapter.class,
        JdbcOrganizationAccountDeletionJobRepositoryAdapter.class,
        JdbcOrganizationExportResourceReader.class,
        JdbcAccountDeletionStepExecutor.class,
        JdbcGovernanceAuditRecorder.class,
        JdbcAuditRetentionGateway.class,
        SpringTransactionExecutor.class
    })
    static class TestApplication {

        /** Vector families and artifact tombstoning are out of this contract's scope. */
        @Bean
        KnowledgeEmbeddingVectorStore knowledgeVectors() {
            return mock(KnowledgeEmbeddingVectorStore.class);
        }

        @Bean
        RepositoryChunkVectorStore chunkVectors() {
            return mock(RepositoryChunkVectorStore.class);
        }

        @Bean
        ArtifactStore artifacts() {
            return mock(ArtifactStore.class);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
