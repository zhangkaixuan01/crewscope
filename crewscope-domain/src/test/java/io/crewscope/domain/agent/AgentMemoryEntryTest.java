package io.crewscope.domain.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workspace.AgentProfileId;
import org.junit.jupiter.api.Test;

/**
 * Invariants of the frozen preference entry and its owner row (M10-I02a, S01 §3.6): key
 * shape, the byte-precise 1KB value bound, write/overwrite/renew version progression, and
 * the monotonic clearance generation.
 */
final class AgentMemoryEntryTest {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-10-04T08:00:00Z");
    private static final UtcTimestamp LATER = UtcTimestamp.parse("2026-10-05T08:00:00Z");

    private final AgentMemoryOwnerKey owner = new AgentMemoryOwnerKey(
            OrganizationId.generate(), TeamId.generate(),
            AgentProfileId.generate(), PrincipalId.generate());
    private final PrincipalId actor = PrincipalId.generate();
    private final AgentMemoryPolicyReference policy =
            AgentMemoryPolicy.defaults().reference();

    // ------------------------------------------------------------------ key shape

    @Test
    void memoryKeysFollowTheFrozenShape() {
        assertEquals("reply-language", new AgentMemoryKey("reply-language").value());
        assertEquals("code-style", new AgentMemoryKey("code-style").value());

        DomainValidationException upper = assertThrows(
                DomainValidationException.class, () -> new AgentMemoryKey("Reply_Language"));
        assertEquals("agentMemory.memoryKey", upper.error().details().get("field"));
        assertThrows(DomainValidationException.class, () -> new AgentMemoryKey("-leading"));
        assertThrows(DomainValidationException.class,
                () -> new AgentMemoryKey("a".repeat(64)));
        assertThrows(NullPointerException.class, () -> new AgentMemoryKey(null));
    }

    // ------------------------------------------------------------------ value bound (UTF-8 bytes)

    @Test
    void theValueBoundCountsBytesNotCharacters() {
        // One CJK character is 3 UTF-8 bytes: 341*3 + 1 = 1024 exactly, 341*3 + 2 = 1025.
        entry("汉".repeat(341) + "a");
        DomainValidationException rejected = assertThrows(
                DomainValidationException.class, () -> entry("汉".repeat(341) + "aa"));
        assertEquals("agentMemory.value", rejected.error().details().get("field"));
        assertThrows(DomainValidationException.class, () -> entry("  \t "));
    }

    // ------------------------------------------------------------------ lifecycle progression

    @Test
    void writeStartsAtVersionZeroAndOverwriteRenewAdvanceIt() {
        AgentMemoryEntry written = entry("中文回复");

        assertEquals(0, written.version());
        assertEquals(actor, written.audit().createdBy().orElseThrow());

        AgentMemoryEntry overwritten = written.overwrite("English replies", LATER, actor, LATER);
        assertEquals(1, overwritten.version());
        assertEquals("English replies", overwritten.value());
        assertEquals(LATER, overwritten.expiresAt());
        assertEquals(actor, overwritten.audit().updatedBy().orElseThrow());

        AgentMemoryEntry renewed = overwritten.renewed(LATER, actor, LATER);
        assertEquals(2, renewed.version());
        assertEquals("English replies", renewed.value(), "renewal never touches the value");
        assertEquals(LATER, renewed.expiresAt());

        assertEquals(0, written.version(), "records are immutable: the source is untouched");
    }

    @Test
    void reconstituteReplaysEveryInvariant() {
        AgentMemoryEntry written = entry("中文回复");
        AgentMemoryEntry restored = AgentMemoryEntry.reconstitute(
                written.owner(), written.policy(), written.memoryKey(), written.value(),
                written.clearanceGeneration(), written.version(), written.expiresAt(),
                written.audit());
        assertEquals(written, restored);
        assertThrows(DomainValidationException.class, () -> AgentMemoryEntry.reconstitute(
                owner, policy, new AgentMemoryKey("valid-key"), "x",
                -1, 0, NOW, AuditMetadata.createdBy(actor, NOW)));
    }

    // ------------------------------------------------------------------ clearance generation

    @Test
    void clearingAdvancesTheOwnerGenerationMonotonically() {
        AgentMemoryOwner first = new AgentMemoryOwner(owner, 0, AuditMetadata.createdBy(actor, NOW));

        AgentMemoryOwner cleared = first.cleared(actor, LATER);
        assertEquals(1, cleared.clearanceGeneration());
        AgentMemoryOwner clearedAgain = cleared.cleared(actor, LATER);
        assertEquals(2, clearedAgain.clearanceGeneration());
        assertEquals(0, first.clearanceGeneration(), "the source owner is immutable");

        assertEquals(actor, clearedAgain.audit().updatedBy().orElseThrow());
        assertTrue(clearedAgain.audit().createdAt().compareTo(clearedAgain.audit().updatedAt()) <= 0);
        DomainValidationException negative = assertThrows(
                DomainValidationException.class,
                () -> new AgentMemoryOwner(owner, -1, AuditMetadata.createdBy(actor, NOW)));
        assertEquals("agentMemory.clearanceGeneration", negative.error().details().get("field"));
    }

    // ------------------------------------------------------------------ fixtures

    private AgentMemoryEntry entry(String value) {
        return AgentMemoryEntry.write(
                owner, policy, new AgentMemoryKey("reply-language"), value,
                0, UtcTimestamp.from(NOW.value().plusSeconds(90L * 24 * 3600)), actor, NOW);
    }
}
