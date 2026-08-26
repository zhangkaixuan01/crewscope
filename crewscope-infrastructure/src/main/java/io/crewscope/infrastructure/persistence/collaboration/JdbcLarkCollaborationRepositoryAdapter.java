package io.crewscope.infrastructure.persistence.collaboration;

import io.crewscope.application.collaboration.LarkExternalTenantRepository;
import io.crewscope.application.collaboration.LarkMemberMappingCursor;
import io.crewscope.application.collaboration.LarkMemberMappingPage;
import io.crewscope.application.collaboration.LarkMemberMappingPageRequest;
import io.crewscope.application.collaboration.LarkMemberMappingRepository;
import io.crewscope.application.collaboration.LarkMemberVerificationProofRepository;
import io.crewscope.domain.collaboration.LarkExternalMemberKey;
import io.crewscope.domain.collaboration.LarkExternalTenant;
import io.crewscope.domain.collaboration.LarkExternalTenantId;
import io.crewscope.domain.collaboration.LarkExternalTenantStatus;
import io.crewscope.domain.collaboration.LarkInternalMemberKey;
import io.crewscope.domain.collaboration.LarkMemberMapping;
import io.crewscope.domain.collaboration.LarkMemberMappingId;
import io.crewscope.domain.collaboration.LarkMemberMappingStatus;
import io.crewscope.domain.collaboration.LarkMemberMappingTerminalReason;
import io.crewscope.domain.collaboration.LarkMemberVerificationProof;
import io.crewscope.domain.collaboration.LarkMemberVerificationProofId;
import io.crewscope.domain.collaboration.LarkOpenId;
import io.crewscope.domain.collaboration.LarkProviderVersion;
import io.crewscope.domain.collaboration.LarkTenantKey;
import io.crewscope.domain.collaboration.LarkUnionId;
import io.crewscope.domain.collaboration.LarkVerificationSource;
import io.crewscope.domain.collaboration.LarkVerificationStatus;
import io.crewscope.domain.provider.ConnectionGrantId;
import io.crewscope.domain.provider.ConnectionId;
import io.crewscope.domain.provider.ProviderBindingId;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamMemberId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL adapter for immutable Lark proofs and versioned, double-unique member mappings. */
@Repository
public class JdbcLarkCollaborationRepositoryAdapter
        implements LarkExternalTenantRepository,
                LarkMemberVerificationProofRepository,
                LarkMemberMappingRepository {

    private static final String TENANT_SELECT = """
            SELECT id, organization_id, connection_id, connection_version,
                   connection_grant_id, connection_grant_version, tenant_key,
                   provider_version, status, verified_at, version
            FROM crewscope.lark_external_tenant
            """;

    private static final String PROOF_SELECT = """
            SELECT id, organization_id, team_id, provider_binding_id,
                   provider_binding_version, connection_id, connection_version,
                   connection_grant_id, connection_grant_version, external_tenant_id,
                   external_tenant_version, tenant_key, open_id, union_id,
                   provider_version, verification_source, status, verified_at, valid_until
            FROM crewscope.lark_member_verification_proof
            """;

    private static final String MAPPING_SELECT = """
            SELECT id, organization_id, team_id, member_id, provider_binding_id,
                   provider_binding_version, connection_id, connection_version,
                   connection_grant_id, connection_grant_version, external_tenant_id,
                   external_tenant_version, tenant_key, open_id, union_id,
                   provider_version, verification_source, verified_at,
                   verified_by_principal_id, status, terminal_reason, version,
                   created_at, created_by_principal_id, updated_at, updated_by_principal_id
            FROM crewscope.lark_member_mapping
            """;

    private final JdbcTemplate jdbc;

    public JdbcLarkCollaborationRepositoryAdapter(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<LarkExternalTenant> findById(
            OrganizationId organizationId, LarkExternalTenantId id) {
        return first(jdbc.query(
                TENANT_SELECT + " WHERE organization_id = ? AND id = ?",
                this::tenant,
                Objects.requireNonNull(organizationId, "organizationId").value(),
                Objects.requireNonNull(id, "id").value()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<LarkExternalTenant> findByConnection(
            OrganizationId organizationId, ConnectionId connectionId) {
        return first(jdbc.query(
                TENANT_SELECT + " WHERE organization_id = ? AND connection_id = ?",
                this::tenant,
                Objects.requireNonNull(organizationId, "organizationId").value(),
                Objects.requireNonNull(connectionId, "connectionId").value()));
    }

    @Override
    @Transactional
    public LarkExternalTenant create(LarkExternalTenant tenant) {
        LarkExternalTenant value = Objects.requireNonNull(tenant, "tenant");
        jdbc.update("""
                INSERT INTO crewscope.lark_external_tenant (
                    id, organization_id, connection_id, connection_version,
                    connection_grant_id, connection_grant_version, tenant_key,
                    provider_version, status, verified_at, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                value.id().value(), value.organizationId().value(), value.connectionId().value(),
                value.connectionVersion(), value.grantId().value(), value.grantVersion(),
                value.tenantKey().value(), value.providerVersion().value(), value.status().name(),
                time(value.verifiedAt()), value.version(), time(value.verifiedAt()),
                time(value.verifiedAt()));
        return value;
    }

    @Override
    @Transactional
    public LarkExternalTenant update(LarkExternalTenant tenant) {
        LarkExternalTenant value = Objects.requireNonNull(tenant, "tenant");
        long expected = previousVersion(value.version(), "LarkExternalTenant");
        int affected = jdbc.update("""
                UPDATE crewscope.lark_external_tenant
                SET connection_version = ?, connection_grant_id = ?,
                    connection_grant_version = ?, tenant_key = ?, provider_version = ?,
                    status = ?, verified_at = ?, version = ?, updated_at = ?
                WHERE organization_id = ? AND id = ? AND version = ?
                """,
                value.connectionVersion(), value.grantId().value(), value.grantVersion(),
                value.tenantKey().value(), value.providerVersion().value(), value.status().name(),
                time(value.verifiedAt()), value.version(), time(value.verifiedAt()),
                value.organizationId().value(), value.id().value(), expected);
        if (affected != 1) {
            throw tenantConflict(value, expected);
        }
        return value;
    }

    @Override
    @Transactional
    public LarkMemberVerificationProof create(LarkMemberVerificationProof proof) {
        LarkMemberVerificationProof value = Objects.requireNonNull(proof, "proof");
        jdbc.update("""
                INSERT INTO crewscope.lark_member_verification_proof (
                    id, organization_id, team_id, provider_binding_id,
                    provider_binding_version, connection_id, connection_version,
                    connection_grant_id, connection_grant_version, external_tenant_id,
                    external_tenant_version, tenant_key, open_id, union_id,
                    provider_version, verification_source, status, verified_at,
                    valid_until, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                value.id().value(), value.organizationId().value(), value.teamId().value(),
                value.providerBindingId().value(), value.providerBindingVersion(),
                value.connectionId().value(), value.connectionVersion(), value.grantId().value(),
                value.grantVersion(), value.externalTenantId().value(),
                value.externalTenantVersion(), value.tenantKey().value(), value.openId().value(),
                value.unionId().value(), value.providerVersion().value(), value.source().name(),
                value.status().name(), time(value.verifiedAt()), time(value.validUntil()),
                time(value.verifiedAt()));
        return value;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<LarkMemberVerificationProof> findById(
            OrganizationId organizationId, LarkMemberVerificationProofId id) {
        return first(jdbc.query(
                PROOF_SELECT + " WHERE organization_id = ? AND id = ?",
                this::proof,
                Objects.requireNonNull(organizationId, "organizationId").value(),
                Objects.requireNonNull(id, "id").value()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<LarkMemberMapping> findById(
            OrganizationId organizationId, LarkMemberMappingId id) {
        return first(jdbc.query(
                MAPPING_SELECT + " WHERE organization_id = ? AND id = ?",
                this::mapping,
                Objects.requireNonNull(organizationId, "organizationId").value(),
                Objects.requireNonNull(id, "id").value()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<LarkMemberMapping> findActiveByInternalKey(LarkInternalMemberKey key) {
        LarkInternalMemberKey value = Objects.requireNonNull(key, "key");
        return first(jdbc.query(
                MAPPING_SELECT + """
                        WHERE organization_id = ? AND team_id = ? AND member_id = ?
                          AND status = 'ACTIVE'
                        """,
                this::mapping,
                value.organizationId().value(), value.teamId().value(), value.memberId().value()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<LarkMemberMapping> findActiveByExternalKey(LarkExternalMemberKey key) {
        LarkExternalMemberKey value = Objects.requireNonNull(key, "key");
        return first(jdbc.query(
                MAPPING_SELECT + """
                        WHERE organization_id = ? AND tenant_key = ? AND open_id = ?
                          AND status = 'ACTIVE'
                        """,
                this::mapping,
                value.organizationId().value(), value.tenantKey().value(), value.openId().value()));
    }

    @Override
    @Transactional(readOnly = true)
    public LarkMemberMappingPage findPage(LarkMemberMappingPageRequest request) {
        LarkMemberMappingPageRequest value = Objects.requireNonNull(request, "request");
        String status = value.status().map(Enum::name).orElse(null);
        OffsetDateTime afterTime = value.after()
                .map(LarkMemberMappingCursor::updatedAt)
                .map(JdbcLarkCollaborationRepositoryAdapter::time)
                .orElse(null);
        UUID afterId = value.after()
                .map(LarkMemberMappingCursor::mappingId)
                .map(LarkMemberMappingId::value)
                .orElse(null);
        List<LarkMemberMapping> candidates = jdbc.query(
                MAPPING_SELECT + """
                        WHERE organization_id = ? AND team_id = ?
                          AND (?::VARCHAR IS NULL OR status = ?::VARCHAR)
                          AND (?::TIMESTAMPTZ IS NULL
                               OR (updated_at, id) < (?::TIMESTAMPTZ, ?::UUID))
                        ORDER BY updated_at DESC, id DESC
                        LIMIT ?
                        """,
                this::mapping,
                value.organizationId().value(), value.teamId().value(), status, status,
                afterTime, afterTime, afterId, value.limit() + 1);
        boolean hasMore = candidates.size() > value.limit();
        List<LarkMemberMapping> page = hasMore
                ? List.copyOf(candidates.subList(0, value.limit()))
                : List.copyOf(candidates);
        return new LarkMemberMappingPage(
                page,
                hasMore
                        ? Optional.of(LarkMemberMappingCursor.from(page.get(page.size() - 1)))
                        : Optional.empty());
    }

    @Override
    @Transactional
    public LarkMemberMapping createActive(LarkMemberMapping mapping) {
        LarkMemberMapping value = Objects.requireNonNull(mapping, "mapping");
        try {
            insertMapping(value);
            return value;
        } catch (DataIntegrityViolationException failure) {
            throw mappingConflict(failure);
        }
    }

    @Override
    @Transactional
    public LarkMemberMapping replaceActive(
            LarkMemberMapping terminatedMapping, LarkMemberMapping replacementMapping) {
        update(Objects.requireNonNull(terminatedMapping, "terminatedMapping"));
        return createActive(Objects.requireNonNull(replacementMapping, "replacementMapping"));
    }

    @Override
    @Transactional
    public LarkMemberMapping update(LarkMemberMapping mapping) {
        LarkMemberMapping value = Objects.requireNonNull(mapping, "mapping");
        long expected = previousVersion(value.version(), "LarkMemberMapping");
        int affected = jdbc.update("""
                UPDATE crewscope.lark_member_mapping
                SET status = ?, terminal_reason = ?, version = ?, updated_at = ?,
                    updated_by_principal_id = ?
                WHERE organization_id = ? AND id = ? AND version = ?
                """,
                value.status().name(), value.terminalReason().map(Enum::name).orElse(null),
                value.version(), time(value.audit().updatedAt()),
                value.audit().updatedBy().orElseThrow().value(), value.organizationId().value(),
                value.id().value(), expected);
        if (affected != 1) {
            throw mappingConflict(value, expected);
        }
        return value;
    }

    private void insertMapping(LarkMemberMapping value) {
        jdbc.update("""
                INSERT INTO crewscope.lark_member_mapping (
                    id, organization_id, team_id, member_id, provider_binding_id,
                    provider_binding_version, connection_id, connection_version,
                    connection_grant_id, connection_grant_version, external_tenant_id,
                    external_tenant_version, tenant_key, open_id, union_id,
                    provider_version, verification_source, verified_at,
                    verified_by_principal_id, status, terminal_reason, version,
                    created_at, created_by_principal_id, updated_at, updated_by_principal_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        ?, ?, ?, ?)
                """,
                value.id().value(), value.organizationId().value(), value.teamId().value(),
                value.memberId().value(), value.providerBindingId().value(),
                value.providerBindingVersion(), value.connectionId().value(),
                value.connectionVersion(), value.grantId().value(), value.grantVersion(),
                value.externalTenantId().value(), value.externalTenantVersion(),
                value.tenantKey().value(), value.openId().value(), value.unionId().value(),
                value.providerVersion().value(), value.verificationSource().name(),
                time(value.verifiedAt()), value.verifiedByPrincipalId().value(),
                value.status().name(), value.terminalReason().map(Enum::name).orElse(null),
                value.version(), time(value.audit().createdAt()),
                value.audit().createdBy().orElseThrow().value(), time(value.audit().updatedAt()),
                value.audit().updatedBy().orElseThrow().value());
    }

    private LarkExternalTenant tenant(ResultSet row, int ignored) throws SQLException {
        return LarkExternalTenant.reconstitute(
                new LarkExternalTenantId(uuid(row, "id")),
                new OrganizationId(uuid(row, "organization_id")),
                new ConnectionId(uuid(row, "connection_id")),
                row.getLong("connection_version"),
                new ConnectionGrantId(uuid(row, "connection_grant_id")),
                row.getLong("connection_grant_version"),
                new LarkTenantKey(row.getString("tenant_key")),
                new LarkProviderVersion(row.getString("provider_version")),
                LarkExternalTenantStatus.valueOf(row.getString("status")),
                timestamp(row, "verified_at"),
                row.getLong("version"));
    }

    private LarkMemberVerificationProof proof(ResultSet row, int ignored) throws SQLException {
        return LarkMemberVerificationProof.reconstitute(
                new LarkMemberVerificationProofId(uuid(row, "id")),
                new OrganizationId(uuid(row, "organization_id")),
                new TeamId(uuid(row, "team_id")),
                new ProviderBindingId(uuid(row, "provider_binding_id")),
                row.getLong("provider_binding_version"),
                new ConnectionId(uuid(row, "connection_id")),
                row.getLong("connection_version"),
                new ConnectionGrantId(uuid(row, "connection_grant_id")),
                row.getLong("connection_grant_version"),
                new LarkExternalTenantId(uuid(row, "external_tenant_id")),
                row.getLong("external_tenant_version"),
                new LarkTenantKey(row.getString("tenant_key")),
                new LarkOpenId(row.getString("open_id")),
                new LarkUnionId(row.getString("union_id")),
                new LarkProviderVersion(row.getString("provider_version")),
                LarkVerificationSource.valueOf(row.getString("verification_source")),
                LarkVerificationStatus.valueOf(row.getString("status")),
                timestamp(row, "verified_at"),
                timestamp(row, "valid_until"));
    }

    private LarkMemberMapping mapping(ResultSet row, int ignored) throws SQLException {
        String reason = row.getString("terminal_reason");
        return LarkMemberMapping.reconstitute(
                new LarkMemberMappingId(uuid(row, "id")),
                new OrganizationId(uuid(row, "organization_id")),
                new TeamId(uuid(row, "team_id")),
                new TeamMemberId(uuid(row, "member_id")),
                new ProviderBindingId(uuid(row, "provider_binding_id")),
                row.getLong("provider_binding_version"),
                new ConnectionId(uuid(row, "connection_id")),
                row.getLong("connection_version"),
                new ConnectionGrantId(uuid(row, "connection_grant_id")),
                row.getLong("connection_grant_version"),
                new LarkExternalTenantId(uuid(row, "external_tenant_id")),
                row.getLong("external_tenant_version"),
                new LarkTenantKey(row.getString("tenant_key")),
                new LarkOpenId(row.getString("open_id")),
                new LarkUnionId(row.getString("union_id")),
                new LarkProviderVersion(row.getString("provider_version")),
                LarkVerificationSource.valueOf(row.getString("verification_source")),
                timestamp(row, "verified_at"),
                new PrincipalId(uuid(row, "verified_by_principal_id")),
                LarkMemberMappingStatus.valueOf(row.getString("status")),
                Optional.ofNullable(reason).map(LarkMemberMappingTerminalReason::valueOf),
                row.getLong("version"),
                new AuditMetadata(
                        Optional.of(new PrincipalId(uuid(row, "created_by_principal_id"))),
                        timestamp(row, "created_at"),
                        Optional.of(new PrincipalId(uuid(row, "updated_by_principal_id"))),
                        timestamp(row, "updated_at")));
    }

    private OptimisticLockConflictException tenantConflict(
            LarkExternalTenant tenant, long expected) {
        Long actual = jdbc.query(
                        "SELECT version FROM crewscope.lark_external_tenant WHERE organization_id = ? AND id = ?",
                        (row, ignored) -> row.getLong(1),
                        tenant.organizationId().value(), tenant.id().value())
                .stream().findFirst().orElse(expected);
        return new OptimisticLockConflictException(
                "LarkExternalTenant", tenant.id(), expected, actual);
    }

    private OptimisticLockConflictException mappingConflict(
            LarkMemberMapping mapping, long expected) {
        Long actual = jdbc.query(
                        "SELECT version FROM crewscope.lark_member_mapping WHERE organization_id = ? AND id = ?",
                        (row, ignored) -> row.getLong(1),
                        mapping.organizationId().value(), mapping.id().value())
                .stream().findFirst().orElse(expected);
        return new OptimisticLockConflictException(
                "LarkMemberMapping", mapping.id(), expected, actual);
    }

    private static DomainValidationException mappingConflict(
            DataIntegrityViolationException failure) {
        DomainValidationException normalized = new DomainValidationException(
                "larkMemberMapping",
                "has a concurrent active internal or external identity conflict");
        normalized.addSuppressed(failure);
        return normalized;
    }

    private static long previousVersion(long version, String aggregate) {
        if (version < 1) {
            throw new IllegalArgumentException(aggregate + " update version must be positive");
        }
        return version - 1;
    }

    private static UUID uuid(ResultSet row, String column) throws SQLException {
        return row.getObject(column, UUID.class);
    }

    private static UtcTimestamp timestamp(ResultSet row, String column) throws SQLException {
        return UtcTimestamp.from(row.getObject(column, OffsetDateTime.class));
    }

    private static OffsetDateTime time(UtcTimestamp value) {
        return Objects.requireNonNull(value, "value").toOffsetDateTime();
    }

    private static <T> Optional<T> first(List<T> values) {
        return Objects.requireNonNull(values, "values").stream().findFirst();
    }
}
