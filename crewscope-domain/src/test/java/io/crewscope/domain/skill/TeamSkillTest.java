package io.crewscope.domain.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.InvalidStateTransitionException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamScope;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Head lifecycle of the Team Skill catalog entry (M10-A03a, ADR-031 §4). */
class TeamSkillTest {

    private static final TeamScope SCOPE =
            new TeamScope(OrganizationId.generate(), TeamId.generate());
    private static final PrincipalId ACTOR = PrincipalId.generate();
    private static final UtcTimestamp AT = UtcTimestamp.parse("2026-10-04T09:00:00Z");

    private static String document(String body) {
        return """
                ---
                name: deploy-runbook-v2
                description: Standard rollback drill for the staging deploy.
                ---

                %s
                """
                .formatted(body);
    }

    private static String otherDocument(String body) {
        return """
                ---
                name: deploy-runbook-v2
                description: Revised drill including the migration tip check.
                ---

                %s
                """
                .formatted(body);
    }

    private static TeamSkill newDraft() {
        return TeamSkill.create(
                SCOPE,
                TeamSkillKey.parse("deploy-runbook-v2"),
                document("Verify the checklist."),
                Optional.empty(),
                ACTOR,
                AT);
    }

    @Test
    void createsADraftHeadWithoutAPointer() {
        TeamSkill skill = newDraft();

        assertEquals(TeamSkillStatus.DRAFT, skill.status());
        assertTrue(skill.draft().isPresent());
        assertTrue(skill.effectiveRevision().isEmpty());
        assertEquals(0L, skill.latestRevision());
        assertEquals(0L, skill.version());
        assertFalse(skill.effectivelyPublished());
        assertTrue(skill.origin().isEmpty());
    }

    @Test
    void refusesADraftWhoseFrontmatterNameDriftsFromTheKey() {
        DomainValidationException failure = assertThrows(
                DomainValidationException.class,
                () -> TeamSkill.create(
                        SCOPE,
                        TeamSkillKey.parse("deploy-runbook-v2"),
                        document("name drift").replace("name: deploy-runbook-v2", "name: other-key"),
                        Optional.empty(),
                        ACTOR,
                        AT));

        assertEquals("teamSkill.draft", failure.error().details().get("field"));
    }

    @Test
    void carriesAnOriginWhenDistilledFromAnExecution() {
        TeamSkillOrigin origin = new TeamSkillOrigin(UUID.randomUUID(), 2);

        TeamSkill skill = TeamSkill.create(
                SCOPE,
                TeamSkillKey.parse("deploy-runbook-v2"),
                document("Distilled body."),
                Optional.of(origin),
                ACTOR,
                AT);

        assertEquals(origin, skill.origin().orElseThrow());
    }

    @Test
    void publishesTheDraftAsRevisionOneAndConsumesIt() {
        TeamSkillPublication publication = newDraft().publish(ACTOR, AT);
        TeamSkill skill = publication.skill();
        TeamSkillVersion version = publication.version();

        assertEquals(TeamSkillStatus.PUBLISHED, skill.status());
        assertEquals(1L, version.revision().value());
        assertTrue(version.previousRevision().isEmpty());
        assertEquals(Optional.of(new TeamSkillRevision(1)), skill.effectiveRevision());
        assertEquals(1L, skill.latestRevision());
        assertTrue(skill.draft().isEmpty());
        assertTrue(skill.effectivelyPublished());
        assertEquals(skill.id(), version.skillId());
        assertEquals(1L, skill.version());
    }

    @Test
    void republishingWithoutANewDraftIsRefused() {
        TeamSkill published = newDraft().publish(ACTOR, AT).skill();

        DomainValidationException failure = assertThrows(
                DomainValidationException.class, () -> published.publish(ACTOR, AT));

        assertEquals("teamSkill.draft", failure.error().details().get("field"));
    }

    @Test
    void publishesASecondRevisionChainedOntoTheFirst() {
        TeamSkill published = newDraft().publish(ACTOR, AT).skill();
        TeamSkill redrafted = published.updateDraft(otherDocument("Longer drill."), ACTOR, AT);

        TeamSkillPublication publication = redrafted.publish(ACTOR, AT);
        TeamSkillVersion version = publication.version();

        assertEquals(2L, version.revision().value());
        assertEquals(Optional.of(new TeamSkillRevision(1)), version.previousRevision());
        assertEquals(Optional.of(new TeamSkillRevision(2)), publication.skill().effectiveRevision());
        assertEquals(2L, publication.skill().latestRevision());
    }

    @Test
    void updateDraftNeverMintsAVersionNorMovesThePointer() {
        TeamSkill published = newDraft().publish(ACTOR, AT).skill();

        TeamSkill redrafted = published.updateDraft(otherDocument("Edited."), ACTOR, AT);

        assertEquals(TeamSkillStatus.PUBLISHED, redrafted.status());
        assertEquals(Optional.of(new TeamSkillRevision(1)), redrafted.effectiveRevision());
        assertEquals(1L, redrafted.latestRevision());
        assertEquals(
                new TeamSkillDraft(otherDocument("Edited.")).content(),
                redrafted.draft().orElseThrow().content());
        assertEquals(2L, redrafted.version());
    }

    @Test
    void disablesALiveSkillAndKeepsTheLastEffectiveRevisionAsEvidence() {
        TeamSkill published = newDraft().publish(ACTOR, AT).skill();

        TeamSkill disabled = published.disable("  Superseded by the platform runbook.  ", ACTOR, AT);

        assertEquals(TeamSkillStatus.DISABLED, disabled.status());
        assertEquals(Optional.of(new TeamSkillRevision(1)), disabled.effectiveRevision());
        assertEquals("Superseded by the platform runbook.", disabled.disableReason().orElseThrow());
        // disable keeps whatever draft state the head had — here the draft was already
        // consumed by the publish, so nothing is resurrected by disabling.
        assertTrue(disabled.draft().isEmpty());
        assertFalse(disabled.effectivelyPublished());
    }

    @Test
    void disableAcceptsNoReasonAndScrapsADraftHead() {
        TeamSkill scrapped = newDraft().disable(null, ACTOR, AT);

        assertEquals(TeamSkillStatus.DISABLED, scrapped.status());
        assertTrue(scrapped.disableReason().isEmpty());
        assertTrue(scrapped.effectiveRevision().isEmpty());
        assertEquals(0L, scrapped.latestRevision());
    }

    @Test
    void refusesAnOverlongDisableReason() {
        TeamSkill published = newDraft().publish(ACTOR, AT).skill();

        DomainValidationException failure = assertThrows(
                DomainValidationException.class,
                () -> published.disable("x".repeat(TeamSkill.MAX_DISABLE_REASON_LENGTH + 1), ACTOR, AT));

        assertEquals("teamSkill.disableReason", failure.error().details().get("field"));
    }

    @Test
    void disablesTwiceIsNotAValidTransition() {
        TeamSkill disabled = newDraft().publish(ACTOR, AT).skill().disable("Retired.", ACTOR, AT);

        assertThrows(InvalidStateTransitionException.class, () -> disabled.disable(null, ACTOR, AT));
    }

    @Test
    void revivesADisabledSkillByPublishingAFreshDraft() {
        TeamSkill disabled = newDraft().publish(ACTOR, AT).skill().disable("Retired.", ACTOR, AT);

        // A disabled head comes back only through a new revision: re-draft (allowed in
        // every status), then publish.
        TeamSkill redrafted = disabled.updateDraft(otherDocument("Revival."), ACTOR, AT);
        TeamSkill revived = redrafted.publish(ACTOR, AT).skill();

        assertEquals(TeamSkillStatus.PUBLISHED, revived.status());
        assertEquals(Optional.of(new TeamSkillRevision(2)), revived.effectiveRevision());
        assertTrue(revived.disableReason().isEmpty());
        assertTrue(revived.effectivelyPublished());
    }

    @Test
    void rollsBackToHistoricalContentAsANewRevisionWithAnEqualDigest() {
        TeamSkillPublication first = newDraft().publish(ACTOR, AT);
        TeamSkill redrafted = first.skill().updateDraft(otherDocument("Drifted."), ACTOR, AT);
        TeamSkillPublication second = redrafted.publish(ACTOR, AT);

        TeamSkillPublication rollback =
                second.skill().rollback(first.version(), ACTOR, AT);

        assertEquals(3L, rollback.version().revision().value());
        assertEquals(Optional.of(new TeamSkillRevision(2)), rollback.version().previousRevision());
        assertEquals(first.version().content(), rollback.version().content());
        assertEquals(first.version().contentHash(), rollback.version().contentHash());
        assertEquals(Optional.of(new TeamSkillRevision(3)), rollback.skill().effectiveRevision());
        assertTrue(rollback.skill().effectivelyPublished());
    }

    @Test
    void refusesToRollBackOntoTheCurrentlyEffectiveRevision() {
        TeamSkillPublication first = newDraft().publish(ACTOR, AT);
        TeamSkill redrafted = first.skill().updateDraft(otherDocument("Drifted."), ACTOR, AT);
        TeamSkillPublication second = redrafted.publish(ACTOR, AT);

        TeamSkillVersionUnchangedException failure = assertThrows(
                TeamSkillVersionUnchangedException.class,
                () -> second.skill().rollback(second.version(), ACTOR, AT));

        assertEquals(
                Long.toString(second.version().revision().value()),
                failure.error().details().get("revision"));
    }

    @Test
    void refusesToRollBackAVersionOfAnotherAggregate() {
        TeamSkillPublication first = newDraft().publish(ACTOR, AT);
        TeamSkillPublication stranger = TeamSkill.create(
                        new TeamScope(OrganizationId.generate(), TeamId.generate()),
                        TeamSkillKey.parse("deploy-runbook-v2"),
                        document("Other team."),
                        Optional.empty(),
                        ACTOR,
                        AT)
                .publish(ACTOR, AT);

        DomainValidationException failure = assertThrows(
                DomainValidationException.class,
                () -> first.skill().rollback(stranger.version(), ACTOR, AT));

        assertEquals("teamSkill.rollback", failure.error().details().get("field"));
    }

    @Test
    void reconstituteUpholdsTheShapeInvariants() {
        TeamSkill published = newDraft().publish(ACTOR, AT).skill();

        // DRAFT may not carry an effective revision…
        assertThrows(DomainValidationException.class, () -> TeamSkill.reconstitute(
                published.id(), SCOPE, published.skillKey(), Optional.empty(),
                TeamSkillStatus.DRAFT, published.effectiveRevision(), 1L,
                published.draft(), Optional.empty(), 1L, published.audit()));
        // …and PUBLISHED may not lack one.
        assertThrows(DomainValidationException.class, () -> TeamSkill.reconstitute(
                published.id(), SCOPE, published.skillKey(), Optional.empty(),
                TeamSkillStatus.PUBLISHED, Optional.empty(), 1L,
                published.draft(), Optional.empty(), 1L, published.audit()));
        // A disable reason only rides on a DISABLED head…
        assertThrows(DomainValidationException.class, () -> TeamSkill.reconstitute(
                published.id(), SCOPE, published.skillKey(), Optional.empty(),
                TeamSkillStatus.PUBLISHED, published.effectiveRevision(), 1L,
                published.draft(), Optional.of("stray"), 1L, published.audit()));
        // …and the pointer may not exceed the latest revision.
        assertThrows(DomainValidationException.class, () -> TeamSkill.reconstitute(
                published.id(), SCOPE, published.skillKey(), Optional.empty(),
                TeamSkillStatus.PUBLISHED, Optional.of(new TeamSkillRevision(2)), 1L,
                published.draft(), Optional.empty(), 1L, published.audit()));

        TeamSkill restored = TeamSkill.reconstitute(
                published.id(), SCOPE, published.skillKey(), Optional.empty(),
                published.status(), published.effectiveRevision(), published.latestRevision(),
                published.draft(), published.disableReason(), published.version(),
                AuditMetadata.createdBy(ACTOR, AT));
        assertEquals(published.id(), restored.id());
        assertEquals(published.status(), restored.status());
        assertEquals(published.effectiveRevision(), restored.effectiveRevision());
        assertEquals(published.version(), restored.version());
        assertEquals(published.draft(), restored.draft());
    }

    @Test
    void restoresAPersistedVersionRowWhoseDigestMustStillMatch() {
        TeamSkillPublication publication = newDraft().publish(ACTOR, AT);
        TeamSkillVersion version = publication.version();

        TeamSkillVersion restored = TeamSkillVersion.reconstitute(
                version.skillId(), version.scope(), version.revision(),
                version.previousRevision(), version.content(), version.contentHash(),
                version.audit());
        assertEquals(version, restored);

        SkillContentHash foreignDigest = SkillContentHash.of("tampered document");
        DomainValidationException failure = assertThrows(
                DomainValidationException.class,
                () -> TeamSkillVersion.reconstitute(
                        version.skillId(), version.scope(), version.revision(),
                        version.previousRevision(), version.content(), foreignDigest,
                        version.audit()));
        assertEquals("teamSkillVersion.contentHash", failure.error().details().get("field"));
    }

    @Test
    void versionsMustChainOntoTheImmediatelyPrecedingRevision() {
        TeamSkillPublication first = newDraft().publish(ACTOR, AT);

        // Revision 1 must not claim a predecessor…
        assertThrows(DomainValidationException.class, () -> TeamSkillVersion.create(
                first.skill().id(), SCOPE, new TeamSkillRevision(1),
                Optional.of(new TeamSkillRevision(1)), document("body"), ACTOR, AT));
        // …and later revisions must claim exactly revision - 1.
        assertThrows(DomainValidationException.class, () -> TeamSkillVersion.create(
                first.skill().id(), SCOPE, new TeamSkillRevision(3),
                Optional.of(new TeamSkillRevision(1)), document("body"), ACTOR, AT));
    }
}
