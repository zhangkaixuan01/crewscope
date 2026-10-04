package io.crewscope.application.skill;

import io.crewscope.domain.agent.ResolvedAgentExecutionConfiguration;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.task.TaskExecutionId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Single load-time resolution boundary for the dynamic Team Skills of one execution
 * attempt (M10-A03b, S01 §3.8): the same resolved triple feeds the Factory's dynamic
 * repository and allow-list, and the sealed injection manifest's SKILL references.
 *
 * <p>The sealed manifest is the attempt-level pin: once an attempt has sealed its
 * manifest, the SKILL references re-resolve from that committed fact (content read
 * back from the immutable version row and hash-verified), so a publish or disable
 * landing mid-execution can never rewrite what an in-flight attempt loaded. Before
 * the first seal the source resolves the policy snapshot's approved keys against the
 * Team catalog's effective published versions.
 */
public interface TeamSkillExecutionSource {

    /**
     * Resolves the loadable dynamic skills for one attempt. Empty when the Skill or
     * injection switch is off — dynamic skills load only with their load evidence —
     * when the snapshot carries no approved keys, or when no approved key currently
     * resolves to a PUBLISHED effective version.
     */
    List<PublishedTeamSkill> resolveForExecution(
            OrganizationId organizationId,
            TeamId teamId,
            Set<String> approvedSkillKeys,
            TaskExecutionId executionId,
            int attempt);

    /**
     * Resolves the same loadable set from an execution's pinned configuration. The
     * append-only configuration row addressed by the snapshot's (profileId, revision)
     * supplies the approved keys — the resolution hash already covers them, so a row
     * that is missing or hashes differently fails closed instead of loading anything.
     * An absent pinned configuration (schema v1 snapshots) loads nothing dynamic.
     */
    List<PublishedTeamSkill> resolveForPinnedExecution(
            OrganizationId organizationId,
            TeamId teamId,
            Optional<ResolvedAgentExecutionConfiguration> pinnedExecution,
            TaskExecutionId executionId,
            int attempt);

    /** One loadable published version with the exact triple the manifest evidence records. */
    record PublishedTeamSkill(
            String skillKey,
            long revision,
            String content,
            String contentHash) {

        public PublishedTeamSkill {
            Objects.requireNonNull(skillKey, "skillKey");
            if (revision < 1) {
                throw new IllegalArgumentException("revision must be positive");
            }
            Objects.requireNonNull(content, "content");
            Objects.requireNonNull(contentHash, "contentHash");
        }
    }
}
