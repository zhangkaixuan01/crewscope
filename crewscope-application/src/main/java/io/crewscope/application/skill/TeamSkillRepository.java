package io.crewscope.application.skill;

import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.skill.TeamSkill;
import io.crewscope.domain.skill.TeamSkillId;
import io.crewscope.domain.skill.TeamSkillKey;
import io.crewscope.domain.skill.TeamSkillRevision;
import io.crewscope.domain.skill.TeamSkillVersion;
import java.util.Optional;

/**
 * Persistence port for Team Skills and their immutable versions. Every read is scoped
 * by the full (organization, Team) coordinate; the PostgreSQL adapter is A03a's
 * infrastructure delivery, and these signatures are its acceptance contract.
 */
public interface TeamSkillRepository {

    /** Inserts a new DRAFT catalog entry; a duplicate (organization, Team, skillKey) conflicts. */
    TeamSkill create(TeamSkill skill);

    /**
     * Atomically persists the optimistic-locked head and, when present, the version row
     * appended by the same publish or rollback. Saving a head whose version no longer
     * matches the committed one must fail with {@link OptimisticLockConflictException}.
     */
    TeamSkill save(TeamSkill skill, Optional<TeamSkillVersion> appendedVersion);

    Optional<TeamSkill> findById(
            OrganizationId organizationId, TeamId teamId, TeamSkillId skillId);

    Optional<TeamSkill> findByKey(
            OrganizationId organizationId, TeamId teamId, TeamSkillKey skillKey);

    /** One keyset page of the Team catalog listing ordered by skill key ascending. */
    TeamSkillPage findByTeam(
            OrganizationId organizationId,
            TeamId teamId,
            TeamSkillFilter filter,
            TeamSkillPageRequest pageRequest);

    Optional<TeamSkillVersion> findVersion(
            OrganizationId organizationId,
            TeamId teamId,
            TeamSkillId skillId,
            TeamSkillRevision revision);

    /** One keyset page of the skill's version history, oldest revision first. */
    TeamSkillVersionPage findVersionHistory(
            OrganizationId organizationId,
            TeamId teamId,
            TeamSkillId skillId,
            TeamSkillVersionPageRequest pageRequest);

    /**
     * The authoritative loading gate: resolves the head's effective pointer and returns
     * empty unless the head is PUBLISHED. Drafts and disabled skills never resolve
     * (ADR-031 §4); A03b's Factory wiring reads here.
     */
    Optional<TeamSkillVersion> findEffectiveVersion(
            OrganizationId organizationId, TeamId teamId, TeamSkillId skillId);
}
