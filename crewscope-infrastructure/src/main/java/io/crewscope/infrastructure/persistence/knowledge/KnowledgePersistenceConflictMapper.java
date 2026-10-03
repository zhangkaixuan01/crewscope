package io.crewscope.infrastructure.persistence.knowledge;

import io.crewscope.domain.knowledge.KnowledgeContentHash;
import io.crewscope.domain.knowledge.KnowledgeEntryId;
import io.crewscope.domain.knowledge.KnowledgeEntryKey;
import io.crewscope.domain.knowledge.KnowledgeEntryKeyConflictException;
import io.crewscope.domain.knowledge.KnowledgeEntryRevision;
import io.crewscope.domain.knowledge.KnowledgeVersionContentConflictException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.team.TeamScope;
import java.sql.SQLException;
import java.util.Locale;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Converts Knowledge uniqueness boundaries into stable domain failures without leaking
 * SQL details: the tenant key maps to a key conflict, the per-entry content hash to a
 * content conflict, and a version primary-key collision to an optimistic-lock conflict
 * (the head UPDATE already excludes concurrent writers, so that branch is defensive).
 */
final class KnowledgePersistenceConflictMapper {

    private KnowledgePersistenceConflictMapper() {}

    static RuntimeException entryHead(
            DataIntegrityViolationException failure, TeamScope scope, KnowledgeEntryKey entryKey) {
        if (hasConstraint(failure, "uk_knowledge_entry_tenant_key")) {
            return new KnowledgeEntryKeyConflictException(scope, entryKey);
        }
        return failure;
    }

    static RuntimeException version(
            DataIntegrityViolationException failure,
            KnowledgeEntryId entryId,
            KnowledgeEntryRevision revision,
            KnowledgeContentHash contentHash) {
        if (hasConstraint(failure, "uk_knowledge_entry_version_content")) {
            return new KnowledgeVersionContentConflictException(entryId, revision, contentHash);
        }
        if (hasConstraint(failure, "knowledge_entry_version_pkey")) {
            return new OptimisticLockConflictException(
                    "KnowledgeEntryVersion", entryId, revision.value(), revision.value());
        }
        return failure;
    }

    private static boolean hasConstraint(Throwable failure, String constraintName) {
        String expected = constraintName.toLowerCase(Locale.ROOT);
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof SQLException sql
                    && "23505".equals(sql.getSQLState())
                    && current.getMessage() != null
                    && current.getMessage().toLowerCase(Locale.ROOT).contains(expected)) {
                return true;
            }
        }
        return false;
    }
}
