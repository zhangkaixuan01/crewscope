package io.crewscope.infrastructure.persistence.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.skill.TeamSkillFilter;
import io.crewscope.application.skill.TeamSkillPage;
import io.crewscope.application.skill.TeamSkillPageRequest;
import io.crewscope.application.skill.TeamSkillVersionPage;
import io.crewscope.application.skill.TeamSkillVersionPageRequest;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.skill.TeamSkill;
import io.crewscope.domain.skill.TeamSkillKey;
import io.crewscope.domain.skill.TeamSkillKeyConflictException;
import io.crewscope.domain.skill.TeamSkillPublication;
import io.crewscope.domain.skill.TeamSkillRevision;
import io.crewscope.domain.skill.TeamSkillStatus;
import io.crewscope.domain.skill.TeamSkillVersion;
import io.crewscope.domain.team.TeamScope;
import io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A03a PostgreSQL proof: the skill repository contract (optimistic head, append-only
 * versions, effective-pointer gate, tenant predicates) behind real SQLSTATE
 * translation, the two keyset cursors, and the rollback digest-coexistence that
 * deliberately diverges from knowledge.
 */
@SpringBootTest(
        classes = JdbcTeamSkillRepositoryAdapterIntegrationTest.TestApplication.class,
        properties = {
            "spring.flyway.schemas=crewscope",
            "spring.flyway.default-schema=crewscope",
            "spring.flyway.create-schemas=true",
            "crewscope.outbox.enabled=false"
        })
class JdbcTeamSkillRepositoryAdapterIntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-10-04T10:00:00Z");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JdbcTeamSkillRepositoryAdapter skills;

    private final OrganizationId organizationId = OrganizationId.generate();
    private final TeamId teamId = TeamId.generate();
    private final PrincipalId actor = PrincipalId.generate();
    private TeamScope scope;

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(JdbcTeamSkillRepositoryAdapter.class)
    static class TestApplication {}

    @BeforeEach
    void seedTenant() {
        jdbc.execute("TRUNCATE TABLE crewscope.organization CASCADE");
        jdbc.update(
                "INSERT INTO crewscope.organization (id, name, status) VALUES (?, 'Skill Org', 'ACTIVE')",
                organizationId.value());
        jdbc.update(
                "INSERT INTO crewscope.team (id, organization_id, name, status) VALUES (?, ?, 'Skill Team', 'ACTIVE')",
                teamId.value(), organizationId.value());
        jdbc.update(
                """
                INSERT INTO crewscope.principal (id, organization_id, principal_type, display_name, status)
                VALUES (?, ?, 'USER', 'Skill owner', 'ACTIVE')
                """,
                actor.value(), organizationId.value());
        scope = new TeamScope(organizationId, teamId);
    }

    @Test
    void roundTripsTheFullLifecycleWithDraftAndDisableReason() {
        TeamSkill created = skills.create(skill("deploy-runbook-v2", "v1"));

        TeamSkill restored = skills.findById(organizationId, teamId, created.id()).orElseThrow();
        assertEquals(created.id(), restored.id());
        assertEquals(TeamSkillStatus.DRAFT, restored.status());
        assertEquals(0, restored.version());
        assertEquals(stored(document("deploy-runbook-v2", "v1")), restored.draft().orElseThrow().content());
        assertTrue(restored.origin().isEmpty());

        TeamSkill edited = skills.save(
                restored.updateDraft(document("deploy-runbook-v2", "v1-edited"), actor, NOW), Optional.empty());
        assertEquals(1, edited.version());

        TeamSkillPublication publication = edited.publish(actor, NOW);
        skills.save(publication.skill(), Optional.of(publication.version()));
        TeamSkill published = skills.findById(organizationId, teamId, created.id()).orElseThrow();
        assertEquals(TeamSkillStatus.PUBLISHED, published.status());
        assertEquals(Optional.of(new TeamSkillRevision(1)), published.effectiveRevision());
        assertTrue(published.draft().isEmpty());

        TeamSkill disabled = skills.save(
                published.disable("Superseded.", actor, NOW), Optional.empty());
        assertEquals(TeamSkillStatus.DISABLED, disabled.status());
        assertEquals(Optional.of(new TeamSkillRevision(1)), disabled.lastEffectiveRevision());
        assertEquals("Superseded.", disabled.disableReason().orElseThrow());

        // A disabled head revives through a fresh draft and a new revision.
        TeamSkill rearmed = skills.save(
                disabled.updateDraft(document("deploy-runbook-v2", "v2"), actor, NOW), Optional.empty());
        TeamSkillPublication revival = rearmed.publish(actor, NOW);
        skills.save(revival.skill(), Optional.of(revival.version()));
        TeamSkill revived = skills.findById(organizationId, teamId, created.id()).orElseThrow();
        assertEquals(TeamSkillStatus.PUBLISHED, revived.status());
        assertEquals(Optional.of(new TeamSkillRevision(2)), revived.effectiveRevision());
        assertTrue(revived.disableReason().isEmpty());
    }

    @Test
    void rollbackAppendsHistoricalContentVerbatimAndDigestsCoexist() {
        TeamSkill created = skills.create(skill("rollback-evidence", "r1"));
        TeamSkillPublication first = created.publish(actor, NOW);
        skills.save(first.skill(), Optional.of(first.version()));
        TeamSkill published = skills.findById(organizationId, teamId, created.id()).orElseThrow();
        TeamSkill drifted = skills.save(
                published.updateDraft(document("rollback-evidence", "r2"), actor, NOW), Optional.empty());
        TeamSkillPublication second = drifted.publish(actor, NOW);
        skills.save(second.skill(), Optional.of(second.version()));

        TeamSkill head = skills.findById(organizationId, teamId, created.id()).orElseThrow();
        TeamSkillVersion target = skills
                .findVersion(organizationId, teamId, created.id(), new TeamSkillRevision(1))
                .orElseThrow();
        TeamSkillPublication rollback = head.rollback(target, actor, NOW);
        skills.save(rollback.skill(), Optional.of(rollback.version()));

        TeamSkillVersion appended = skills
                .findVersion(organizationId, teamId, created.id(), new TeamSkillRevision(3))
                .orElseThrow();
        assertEquals(first.version().content(), appended.content());
        assertEquals(first.version().contentHash(), appended.contentHash());
        // The deliberate divergence from knowledge: no unique constraint on the digest.
        assertEquals(
                2L,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM crewscope.team_skill_version"
                                + " WHERE skill_id = ? AND content_hash = ?",
                        Long.class, created.id().value(),
                        first.version().contentHash().value()));
        assertEquals(
                appended.contentHash(),
                skills.findEffectiveVersion(organizationId, teamId, created.id())
                        .orElseThrow().contentHash());
    }

    @Test
    void teamListingPagesBySkillKeyAscendingAndFiltersByStatus() {
        skills.create(skill("alpha-drill", "a"));
        skills.create(skill("bravo-drill", "b"));
        TeamSkill charlie = skills.create(skill("charlie-drill", "c"));
        TeamSkillPublication publication = charlie.publish(actor, NOW);
        skills.save(publication.skill(), Optional.of(publication.version()));

        TeamSkillPage firstPage = skills.findByTeam(
                organizationId, teamId, TeamSkillFilter.all(),
                new TeamSkillPageRequest(Optional.empty(), 2));
        assertEquals(List.of("alpha-drill", "bravo-drill"),
                firstPage.items().stream().map(item -> item.skillKey().value()).toList());
        assertEquals(Optional.of(TeamSkillKey.parse("bravo-drill")), firstPage.nextSkillKey());

        TeamSkillPage secondPage = skills.findByTeam(
                organizationId, teamId, TeamSkillFilter.all(),
                new TeamSkillPageRequest(firstPage.nextSkillKey(), 2));
        assertEquals(List.of("charlie-drill"),
                secondPage.items().stream().map(item -> item.skillKey().value()).toList());
        assertTrue(secondPage.nextSkillKey().isEmpty());

        TeamSkillPage publishedOnly = skills.findByTeam(
                organizationId, teamId, TeamSkillFilter.effectivelyPublished(),
                new TeamSkillPageRequest(Optional.empty(), 10));
        assertEquals(List.of("charlie-drill"),
                publishedOnly.items().stream().map(item -> item.skillKey().value()).toList());
    }

    @Test
    void versionHistoryPagesByRevisionAscending() {
        TeamSkill created = skills.create(skill("deep-history", "h1"));
        for (int index = 1; index <= 3; index++) {
            TeamSkill head = skills.findById(organizationId, teamId, created.id()).orElseThrow();
            if (head.draft().isEmpty()) {
                head = skills.save(
                        head.updateDraft(document("deep-history", "h" + (index + 1)), actor, NOW),
                        Optional.empty());
            }
            TeamSkillPublication publication = head.publish(actor, NOW);
            skills.save(publication.skill(), Optional.of(publication.version()));
        }

        TeamSkillVersionPage firstPage = skills.findVersionHistory(
                organizationId, teamId, created.id(),
                new TeamSkillVersionPageRequest(Optional.empty(), 2));
        assertEquals(List.of(1L, 2L),
                firstPage.items().stream().map(item -> item.revision().value()).toList());
        assertEquals(Optional.of(new TeamSkillRevision(2)), firstPage.nextRevision());

        TeamSkillVersionPage secondPage = skills.findVersionHistory(
                organizationId, teamId, created.id(),
                new TeamSkillVersionPageRequest(firstPage.nextRevision(), 2));
        assertEquals(List.of(3L),
                secondPage.items().stream().map(item -> item.revision().value()).toList());
        assertTrue(secondPage.nextRevision().isEmpty());
    }

    @Test
    void staleHeadVersionIsRejectedAndTheStoredHeadWins() {
        TeamSkill created = skills.create(skill("stale-head", "s1"));
        skills.save(
                created.updateDraft(document("stale-head", "s2"), actor, NOW), Optional.empty());

        // Saving the original head again is one version behind the stored head.
        assertThrows(
                OptimisticLockConflictException.class,
                () -> skills.save(
                        created.updateDraft(document("stale-head", "s3"), actor, NOW), Optional.empty()));
        TeamSkill stored = skills.findById(organizationId, teamId, created.id()).orElseThrow();
        assertEquals(1, stored.version());
        assertEquals(stored(document("stale-head", "s2")), stored.draft().orElseThrow().content());
    }

    @Test
    void duplicateTenantKeyTranslatesToAKeyConflict() {
        skills.create(skill("dup-key", "d1"));
        assertThrows(
                TeamSkillKeyConflictException.class,
                () -> skills.create(skill("dup-key", "d2")));
    }

    @Test
    void effectiveVersionFollowsTheHeadPointerOnly() {
        TeamSkill draft = skills.create(skill("still-draft", "e1"));
        assertTrue(skills.findEffectiveVersion(organizationId, teamId, draft.id()).isEmpty());

        TeamSkill live = skills.create(skill("pointer-skill", "e2"));
        TeamSkillPublication publication = live.publish(actor, NOW);
        skills.save(publication.skill(), Optional.of(publication.version()));
        assertEquals(
                Optional.of(new TeamSkillRevision(1)),
                skills.findEffectiveVersion(organizationId, teamId, live.id())
                        .map(TeamSkillVersion::revision));

        TeamSkill published = skills.findById(organizationId, teamId, live.id()).orElseThrow();
        skills.save(published.disable("Retired.", actor, NOW), Optional.empty());
        assertTrue(skills.findEffectiveVersion(organizationId, teamId, live.id()).isEmpty());
    }

    @Test
    void crossTenantQueriesStayEmpty() {
        TeamSkill created = skills.create(skill("tenant-guard", "t1"));
        TeamSkillPublication publication = created.publish(actor, NOW);
        skills.save(publication.skill(), Optional.of(publication.version()));
        OrganizationId stranger = OrganizationId.generate();
        TeamId strangerTeam = TeamId.generate();

        assertTrue(skills.findById(stranger, teamId, created.id()).isEmpty());
        assertTrue(skills.findByKey(stranger, teamId, created.skillKey()).isEmpty());
        assertTrue(skills.findByTeam(
                stranger, teamId, TeamSkillFilter.all(),
                new TeamSkillPageRequest(Optional.empty(), 10)).items().isEmpty());
        assertTrue(skills.findVersion(
                stranger, teamId, created.id(), new TeamSkillRevision(1)).isEmpty());
        assertTrue(skills.findVersion(
                organizationId, strangerTeam, created.id(), new TeamSkillRevision(1)).isEmpty());
        assertTrue(skills.findEffectiveVersion(stranger, teamId, created.id()).isEmpty());
        assertEquals(
                1L,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM crewscope.team_skill_version WHERE skill_id = ?",
                        Long.class, created.id().value()));
    }

    private TeamSkill skill(String skillKey, String marker) {
        return TeamSkill.create(
                scope,
                TeamSkillKey.parse(skillKey),
                document(skillKey, marker),
                Optional.empty(),
                actor,
                NOW);
    }

    /** The frontmatter name must equal the skill key; only the body may vary per call. */
    private static String document(String name, String marker) {
        return """
                ---
                name: %s
                description: Marker %s of the drill.
                ---

                Body of %s.
                """
                .formatted(name, marker, marker);
    }

    /** What the domain keeps after constructing the draft: the document, trailing whitespace stripped. */
    private static String stored(String content) {
        return new io.crewscope.domain.skill.TeamSkillDraft(content).content();
    }
}
