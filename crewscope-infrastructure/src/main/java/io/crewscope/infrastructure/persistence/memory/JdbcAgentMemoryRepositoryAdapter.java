package io.crewscope.infrastructure.persistence.memory;

import io.crewscope.application.memory.AgentMemoryClearance;
import io.crewscope.application.memory.AgentMemoryRepository;
import io.crewscope.domain.agent.AgentMemoryEntry;
import io.crewscope.domain.agent.AgentMemoryKey;
import io.crewscope.domain.agent.AgentMemoryOwner;
import io.crewscope.domain.agent.AgentMemoryOwnerKey;
import io.crewscope.domain.agent.AgentMemoryPolicyReference;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workspace.AgentProfileId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * PostgreSQL store for the M10-I02a agent assistant memory lifecycle. The owner row is the
 * serialization point: writes lock it ({@code SELECT ... FOR UPDATE}) and compare the
 * clearance generation before touching entries, so a write racing a clear is rejected with
 * {@link OptimisticLockConflictException} instead of resurrecting cleared rows. Reads join
 * the owner's current generation, which is what makes old-generation rows invisible.
 */
@Repository
public class JdbcAgentMemoryRepositoryAdapter implements AgentMemoryRepository {

    private final JdbcTemplate jdbc;

    public JdbcAgentMemoryRepositoryAdapter(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    @Transactional
    public AgentMemoryOwner ensureOwner(AgentMemoryOwnerKey key, PrincipalId actor, UtcTimestamp now) {
        AgentMemoryOwnerKey requiredKey = requireKey(key);
        jdbc.update(
                """
                INSERT INTO crewscope.agent_memory_owner (
                    id, organization_id, team_id, agent_profile_id, owner_principal_id,
                    clearance_generation, created_at, created_by_principal_id,
                    updated_at, updated_by_principal_id)
                VALUES (?, ?, ?, ?, ?, 0, ?, ?, ?, ?)
                ON CONFLICT (organization_id, team_id, agent_profile_id, owner_principal_id)
                    DO NOTHING
                """,
                UUID.randomUUID(), requiredKey.organizationId().value(), requiredKey.teamId().value(),
                requiredKey.agentProfileId().value(), requiredKey.ownerPrincipalId().value(),
                time(now), actor.value(), time(now), actor.value());
        return findOwner(requiredKey).orElseThrow();
    }

    @Override
    public Optional<AgentMemoryOwner> findOwner(AgentMemoryOwnerKey key) {
        List<AgentMemoryOwner> owners = jdbc.query(
                """
                SELECT * FROM crewscope.agent_memory_owner
                WHERE organization_id = ? AND team_id = ?
                  AND agent_profile_id = ? AND owner_principal_id = ?
                """,
                this::owner,
                key.organizationId().value(), key.teamId().value(),
                key.agentProfileId().value(), key.ownerPrincipalId().value());
        return one(owners);
    }

    @Override
    public List<AgentMemoryEntry> findVisible(
            AgentMemoryOwnerKey key, AgentMemoryPolicyReference policy, UtcTimestamp now) {
        requireKey(key);
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(now, "now");
        return jdbc.query(
                """
                SELECT e.* FROM crewscope.agent_memory_entry e
                JOIN crewscope.agent_memory_owner o
                  ON e.organization_id = o.organization_id AND e.team_id = o.team_id
                 AND e.agent_profile_id = o.agent_profile_id
                 AND e.owner_principal_id = o.owner_principal_id
                WHERE e.organization_id = ? AND e.team_id = ?
                  AND e.agent_profile_id = ? AND e.owner_principal_id = ?
                  AND e.policy_id = ? AND e.policy_version = ?
                  AND e.clearance_generation = o.clearance_generation
                  AND e.expires_at > ?
                ORDER BY e.memory_key ASC
                """,
                this::entry,
                key.organizationId().value(), key.teamId().value(),
                key.agentProfileId().value(), key.ownerPrincipalId().value(),
                policy.policyId(), policy.version(), time(now));
    }

    @Override
    @Transactional
    public AgentMemoryEntry upsert(AgentMemoryEntry entry) {
        AgentMemoryEntry value = Objects.requireNonNull(entry, "entry");
        AgentMemoryOwnerKey key = requireKey(value.owner());
        // Lock the owner row first: this serializes concurrent writes and clears of one space
        // and is where the anti-resurrection generation check happens.
        jdbc.update(
                """
                INSERT INTO crewscope.agent_memory_owner (
                    id, organization_id, team_id, agent_profile_id, owner_principal_id,
                    clearance_generation, created_at, created_by_principal_id,
                    updated_at, updated_by_principal_id)
                VALUES (?, ?, ?, ?, ?, 0, ?, ?, ?, ?)
                ON CONFLICT (organization_id, team_id, agent_profile_id, owner_principal_id)
                    DO NOTHING
                """,
                UUID.randomUUID(), key.organizationId().value(), key.teamId().value(),
                key.agentProfileId().value(), key.ownerPrincipalId().value(),
                time(value.audit().updatedAt()), key.ownerPrincipalId().value(),
                time(value.audit().updatedAt()), key.ownerPrincipalId().value());
        Long generation = jdbc.queryForObject(
                """
                SELECT clearance_generation FROM crewscope.agent_memory_owner
                WHERE organization_id = ? AND team_id = ?
                  AND agent_profile_id = ? AND owner_principal_id = ?
                FOR UPDATE
                """,
                Long.class,
                key.organizationId().value(), key.teamId().value(),
                key.agentProfileId().value(), key.ownerPrincipalId().value());
        if (generation == null || generation != value.clearanceGeneration()) {
            throw new OptimisticLockConflictException(
                    "AgentMemoryEntry",
                    key.agentProfileId().value() + ":" + value.memoryKey().value(),
                    value.clearanceGeneration(), generation == null ? 0 : generation);
        }
        List<AgentMemoryEntry> existingRows = jdbc.query(
                """
                SELECT * FROM crewscope.agent_memory_entry
                WHERE organization_id = ? AND team_id = ?
                  AND agent_profile_id = ? AND owner_principal_id = ?
                  AND policy_id = ? AND policy_version = ? AND memory_key = ?
                FOR UPDATE
                """,
                this::entry,
                key.organizationId().value(), key.teamId().value(),
                key.agentProfileId().value(), key.ownerPrincipalId().value(),
                value.policy().policyId(), value.policy().version(), value.memoryKey().value());
        if (existingRows.isEmpty()) {
            insert(value);
            return value;
        }
        AgentMemoryEntry existing = one(existingRows).orElseThrow();
        // An overwrite absorbs a stale-generation survivor row into the current
        // generation, so the returned entry and the UPDATE below both carry the
        // generation the service validated under the owner row lock.
        AgentMemoryEntry overwritten = new AgentMemoryEntry(
                existing.owner(), existing.policy(), existing.memoryKey(),
                value.value(), value.clearanceGeneration(), existing.version() + 1,
                value.expiresAt(),
                existing.audit().modifiedBy(key.ownerPrincipalId(), value.audit().updatedAt()));
        jdbc.update(
                """
                UPDATE crewscope.agent_memory_entry
                SET value = ?, version = ?, expires_at = ?,
                    clearance_generation = ?,
                    updated_at = ?, updated_by_principal_id = ?
                WHERE organization_id = ? AND team_id = ?
                  AND agent_profile_id = ? AND owner_principal_id = ?
                  AND policy_id = ? AND policy_version = ? AND memory_key = ?
                """,
                overwritten.value(), overwritten.version(), time(overwritten.expiresAt()),
                value.clearanceGeneration(),
                time(overwritten.audit().updatedAt()), updater(overwritten.audit()),
                key.organizationId().value(), key.teamId().value(),
                key.agentProfileId().value(), key.ownerPrincipalId().value(),
                value.policy().policyId(), value.policy().version(), value.memoryKey().value());
        return overwritten;
    }

    @Override
    @Transactional
    public boolean renew(
            AgentMemoryOwnerKey key,
            AgentMemoryPolicyReference policy,
            AgentMemoryKey memoryKey,
            UtcTimestamp newExpiresAt,
            PrincipalId actor,
            UtcTimestamp now) {
        requireKey(key);
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(memoryKey, "memoryKey");
        int renewed = jdbc.update(
                """
                UPDATE crewscope.agent_memory_entry e
                SET version = e.version + 1, expires_at = ?,
                    updated_at = ?, updated_by_principal_id = ?
                FROM crewscope.agent_memory_owner o
                WHERE e.organization_id = o.organization_id AND e.team_id = o.team_id
                  AND e.agent_profile_id = o.agent_profile_id
                  AND e.owner_principal_id = o.owner_principal_id
                  AND e.organization_id = ? AND e.team_id = ?
                  AND e.agent_profile_id = ? AND e.owner_principal_id = ?
                  AND e.policy_id = ? AND e.policy_version = ? AND e.memory_key = ?
                  AND e.clearance_generation = o.clearance_generation
                  AND e.expires_at > ?
                """,
                time(newExpiresAt), time(now), actor.value(),
                key.organizationId().value(), key.teamId().value(),
                key.agentProfileId().value(), key.ownerPrincipalId().value(),
                policy.policyId(), policy.version(), memoryKey.value(), time(now));
        return renewed == 1;
    }

    @Override
    @Transactional
    public AgentMemoryClearance clear(AgentMemoryOwnerKey key, PrincipalId actor, UtcTimestamp now) {
        AgentMemoryOwnerKey requiredKey = requireKey(key);
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(now, "now");
        ensureOwner(requiredKey, actor, now);
        Long nextGeneration = jdbc.queryForObject(
                """
                UPDATE crewscope.agent_memory_owner
                SET clearance_generation = clearance_generation + 1,
                    updated_at = ?, updated_by_principal_id = ?
                WHERE organization_id = ? AND team_id = ?
                  AND agent_profile_id = ? AND owner_principal_id = ?
                RETURNING clearance_generation
                """,
                Long.class,
                time(now), actor.value(),
                requiredKey.organizationId().value(), requiredKey.teamId().value(),
                requiredKey.agentProfileId().value(), requiredKey.ownerPrincipalId().value());
        // Delete every generation below the freshly bumped one, not the generation read
        // before the bump: two interleaved clears must not leave the entries a concurrent
        // upsert filed under the in-between generation (the contract deletes every
        // current-generation entry across all policy spaces).
        int deleted = jdbc.update(
                """
                DELETE FROM crewscope.agent_memory_entry
                WHERE organization_id = ? AND team_id = ?
                  AND agent_profile_id = ? AND owner_principal_id = ?
                  AND clearance_generation < ?
                """,
                requiredKey.organizationId().value(), requiredKey.teamId().value(),
                requiredKey.agentProfileId().value(), requiredKey.ownerPrincipalId().value(),
                nextGeneration == null ? 0 : nextGeneration);
        return new AgentMemoryClearance(deleted, nextGeneration == null ? 0 : nextGeneration);
    }

    @Override
    @Transactional
    public long deleteSweepable(UtcTimestamp now, int limit) {
        Objects.requireNonNull(now, "now");
        if (limit <= 0) {
            return 0;
        }
        return jdbc.update(
                """
                DELETE FROM crewscope.agent_memory_entry
                WHERE id IN (
                    SELECT e.id FROM crewscope.agent_memory_entry e
                    LEFT JOIN crewscope.agent_memory_owner o
                      ON e.organization_id = o.organization_id AND e.team_id = o.team_id
                     AND e.agent_profile_id = o.agent_profile_id
                     AND e.owner_principal_id = o.owner_principal_id
                    WHERE e.expires_at <= ?
                       OR o.id IS NULL
                       OR e.clearance_generation <> o.clearance_generation
                    LIMIT ?
                )
                """,
                time(now), limit);
    }

    // ------------------------------------------------------------------ mapping

    private void insert(AgentMemoryEntry value) {
        AgentMemoryOwnerKey key = value.owner();
        jdbc.update(
                """
                INSERT INTO crewscope.agent_memory_entry (
                    id, organization_id, team_id, agent_profile_id, owner_principal_id,
                    policy_id, policy_version, memory_key, value,
                    clearance_generation, version, expires_at,
                    created_at, created_by_principal_id, updated_at, updated_by_principal_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(), key.organizationId().value(), key.teamId().value(),
                key.agentProfileId().value(), key.ownerPrincipalId().value(),
                value.policy().policyId(), value.policy().version(), value.memoryKey().value(),
                value.value(), value.clearanceGeneration(), value.version(),
                time(value.expiresAt()),
                time(value.audit().createdAt()), creator(value.audit()),
                time(value.audit().updatedAt()), updater(value.audit()));
    }

    private AgentMemoryOwner owner(ResultSet row, int ignored) throws SQLException {
        return new AgentMemoryOwner(
                new AgentMemoryOwnerKey(
                        new OrganizationId(uuid(row, "organization_id")),
                        new TeamId(uuid(row, "team_id")),
                        new AgentProfileId(uuid(row, "agent_profile_id")),
                        new PrincipalId(uuid(row, "owner_principal_id"))),
                row.getLong("clearance_generation"),
                audit(row));
    }

    private AgentMemoryEntry entry(ResultSet row, int ignored) throws SQLException {
        return AgentMemoryEntry.reconstitute(
                new AgentMemoryOwnerKey(
                        new OrganizationId(uuid(row, "organization_id")),
                        new TeamId(uuid(row, "team_id")),
                        new AgentProfileId(uuid(row, "agent_profile_id")),
                        new PrincipalId(uuid(row, "owner_principal_id"))),
                new AgentMemoryPolicyReference(
                        uuid(row, "policy_id"), row.getLong("policy_version")),
                new AgentMemoryKey(row.getString("memory_key")),
                row.getString("value"),
                row.getLong("clearance_generation"),
                row.getLong("version"),
                timestamp(row, "expires_at"),
                audit(row));
    }

    private static AuditMetadata audit(ResultSet row) throws SQLException {
        return new AuditMetadata(
                Optional.of(new PrincipalId(uuid(row, "created_by_principal_id"))),
                timestamp(row, "created_at"),
                Optional.of(new PrincipalId(uuid(row, "updated_by_principal_id"))),
                timestamp(row, "updated_at"));
    }

    private static UUID creator(AuditMetadata audit) {
        return audit.createdBy().orElseThrow().value();
    }

    private static UUID updater(AuditMetadata audit) {
        return audit.updatedBy().orElseThrow().value();
    }

    private static OffsetDateTime time(UtcTimestamp value) {
        return value.toOffsetDateTime();
    }

    private static UtcTimestamp timestamp(ResultSet row, String column) throws SQLException {
        return UtcTimestamp.from(row.getObject(column, OffsetDateTime.class).toInstant());
    }

    private static UUID uuid(ResultSet row, String column) throws SQLException {
        return row.getObject(column, UUID.class);
    }

    private static AgentMemoryOwnerKey requireKey(AgentMemoryOwnerKey key) {
        return Objects.requireNonNull(key, "key");
    }

    private static <T> Optional<T> one(List<T> values) {
        if (values.size() > 1) {
            throw new IllegalStateException(
                    "Tenant-scoped Agent memory query returned multiple rows");
        }
        return values.stream().findFirst();
    }
}
