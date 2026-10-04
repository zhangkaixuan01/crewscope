package io.crewscope.infrastructure.persistence.skill;

import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.skill.TeamSkillId;
import io.crewscope.domain.skill.TeamSkillKey;
import io.crewscope.domain.skill.TeamSkillKeyConflictException;
import io.crewscope.domain.team.TeamScope;
import java.sql.SQLException;
import java.util.Locale;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Converts Team Skill uniqueness boundaries into stable domain failures without leaking
 * SQL details: the tenant key maps to a key conflict, and a version primary-key
 * collision to an optimistic-lock conflict — the head UPDATE already excludes
 * concurrent writers, so that branch is defensive. Content hashes are deliberately
 * not mapped: a rollback re-publishes historical content, so duplicate digests are
 * legal (ADR-031 §4, unlike knowledge).
 */
final class TeamSkillPersistenceConflictMapper {

    private TeamSkillPersistenceConflictMapper() {}

    static RuntimeException skillHead(
            DataIntegrityViolationException failure, TeamScope scope, TeamSkillKey skillKey) {
        if (hasConstraint(failure, "uk_team_skill_tenant_key")) {
            return new TeamSkillKeyConflictException(scope, skillKey);
        }
        return failure;
    }

    static RuntimeException version(
            DataIntegrityViolationException failure,
            TeamSkillId skillId,
            long revision) {
        if (hasConstraint(failure, "team_skill_version_pkey")) {
            return new OptimisticLockConflictException(
                    "TeamSkillVersion", skillId, revision, revision);
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
