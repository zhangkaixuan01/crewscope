package io.crewscope.infrastructure.persistence.organization;

import io.crewscope.application.artifact.ArtifactDescriptor;
import io.crewscope.application.artifact.ArtifactMutationContext;
import io.crewscope.application.artifact.ArtifactStore;
import io.crewscope.application.artifact.ArtifactTombstoneReason;
import io.crewscope.application.organization.governance.AccountDeletionStepExecutor;
import io.crewscope.application.organization.governance.DeletionStepKey;
import io.crewscope.domain.shared.id.ArtifactId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * JDBC + ArtifactStore implementation of the six frozen deletion steps (S01 freeze 3.4).
 *
 * <p>Every step is idempotent: re-running after a restored backup re-observes the same live
 * counts and converges as a no-op or a CONFLICT settlement. TEAM_MEMBER reuses the M9b soft
 * removal (status REMOVED, row retained) so conversation and agent_profile ownership references
 * keep pointing at the historical member — v1 keeps those content assets in place. The terminal
 * identity step disables every login surface in place (the V31 credential-history guards forbid
 * deleting login rows) and bumps the account's security version so live sessions die with it;
 * wholesale principal row deletion is a declared later increment (the created_by/updated_by
 * RESTRICT audit references span thirty tables).
 */
@Repository
public class JdbcAccountDeletionStepExecutor implements AccountDeletionStepExecutor {

    /** Descriptor walk page size for the artifact tombstone step. */
    private static final int ARTIFACT_PAGE = 200;

    private final NamedParameterJdbcTemplate jdbc;
    private final ArtifactStore artifacts;

    public JdbcAccountDeletionStepExecutor(
            NamedParameterJdbcTemplate jdbc, ArtifactStore artifacts) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
    }

    @Override
    @Transactional(readOnly = true)
    public long countStep(
            OrganizationId organizationId,
            PrincipalId targetPrincipalId,
            DeletionStepKey stepKey,
            String targetRef) {
        MapSqlParameterSource parameters =
                parameters(organizationId, targetPrincipalId, targetRef);
        return switch (stepKey) {
            case REVOKE_TASK_CREDENTIAL_GRANTS -> count(jdbc, """
                    SELECT COUNT(*) FROM crewscope.task_credential_grant
                    WHERE organization_id = :organizationId
                      AND execution_principal_id = :targetPrincipalId
                      AND status = 'ACTIVE'
                    """, parameters);
            case REVOKE_SUBJECT_CREDENTIALS -> count(jdbc, """
                    SELECT COUNT(*) FROM crewscope.credential_secret
                     WHERE organization_id = :organizationId
                       AND principal_id = :targetPrincipalId
                       AND status <> 'REVOKED'
                    """, parameters);
            case TOMBSTONE_ACCOUNT_ARTIFACTS -> countTargetArtifacts(organizationId, targetPrincipalId);
            case DELETE_DERIVED_ROWS -> countDerivedRows(organizationId, targetPrincipalId, targetRef);
            case TOMBSTONE_IDENTITY -> count(jdbc, """
                    SELECT (SELECT COUNT(*) FROM crewscope.login_identity li
                             JOIN crewscope.account_organization_binding b
                               ON b.account_id = li.account_id
                              AND b.organization_id = :organizationId
                              AND b.principal_id = :targetPrincipalId
                             WHERE li.status = 'ACTIVE')
                         + (SELECT COUNT(*) FROM crewscope.account_organization_binding
                             WHERE organization_id = :organizationId
                               AND principal_id = :targetPrincipalId
                               AND status = 'ACTIVE')
                         + 2
                    """, parameters);
            case RECORD_BACKUP_RETENTION -> 0L;
        };
    }

    @Override
    @Transactional
    public int executeStep(
            OrganizationId organizationId,
            PrincipalId targetPrincipalId,
            DeletionStepKey stepKey,
            String targetRef) {
        MapSqlParameterSource parameters =
                parameters(organizationId, targetPrincipalId, targetRef);
        return switch (stepKey) {
            case REVOKE_TASK_CREDENTIAL_GRANTS -> jdbc.update("""
                    UPDATE crewscope.task_credential_grant
                       SET status = 'REVOKED',
                           termination_reason = 'ACCOUNT_DELETION_GOVERNANCE',
                           updated_at = CURRENT_TIMESTAMP
                     WHERE organization_id = :organizationId
                       AND execution_principal_id = :targetPrincipalId
                       AND status = 'ACTIVE'
                    """, parameters);
            case REVOKE_SUBJECT_CREDENTIALS -> jdbc.update("""
                    UPDATE crewscope.credential_secret
                       SET status = 'REVOKED', revoked_at = CURRENT_TIMESTAMP,
                           updated_at = CURRENT_TIMESTAMP
                     WHERE organization_id = :organizationId
                       AND principal_id = :targetPrincipalId
                       AND status <> 'REVOKED'
                    """, parameters);
            case TOMBSTONE_ACCOUNT_ARTIFACTS -> tombstoneTargetArtifacts(
                    organizationId, targetPrincipalId);
            case DELETE_DERIVED_ROWS -> executeDerivedRows(
                    organizationId, targetPrincipalId, targetRef, parameters);
            // The V31 credential-history guards forbid deleting login rows, so the terminal
            // step disables every login surface in place: the account can no longer
            // authenticate, while the rows survive as the credential-history record. The
            // login guard treats a status flip and an authentication-time bump as mutually
            // exclusive facts (each UPDATE must change exactly one), so the disable must not
            // touch last_authenticated_at.
            case TOMBSTONE_IDENTITY -> jdbc.update("""
                    UPDATE crewscope.login_identity
                       SET status = 'DISABLED',
                           version = version + 1,
                           updated_at = CURRENT_TIMESTAMP
                     WHERE status = 'ACTIVE'
                       AND account_id IN (SELECT account_id
                                            FROM crewscope.account_organization_binding
                                           WHERE organization_id = :organizationId
                                             AND principal_id = :targetPrincipalId)
                    """, parameters)
                    + jdbc.update("""
                    UPDATE crewscope.account_organization_binding
                       SET status = 'DISABLED', version = version + 1,
                           updated_at = CURRENT_TIMESTAMP
                     WHERE organization_id = :organizationId
                       AND principal_id = :targetPrincipalId
                       AND status = 'ACTIVE'
                    """, parameters)
                    + jdbc.update("""
                    UPDATE crewscope.principal
                       SET status = 'DISABLED', updated_at = CURRENT_TIMESTAMP
                     WHERE organization_id = :organizationId
                       AND id = :targetPrincipalId
                       AND status <> 'DISABLED'
                    """, parameters)
                    + jdbc.update("""
                    UPDATE crewscope.user_account
                       SET status = 'DISABLED', security_version = security_version + 1,
                           version = version + 1, updated_at = CURRENT_TIMESTAMP
                     WHERE id IN (SELECT account_id
                                    FROM crewscope.account_organization_binding
                                   WHERE organization_id = :organizationId
                                     AND principal_id = :targetPrincipalId)
                       AND status = 'ACTIVE'
                    """, parameters);
            case RECORD_BACKUP_RETENTION -> 0;
        };
    }

    private long countDerivedRows(
            OrganizationId organizationId, PrincipalId targetPrincipalId, String targetRef) {
        MapSqlParameterSource parameters =
                parameters(organizationId, targetPrincipalId, targetRef);
        return switch (targetRef) {
            case "TEAM_MEMBER_ROLE" -> count(jdbc, """
                    SELECT COUNT(*) FROM crewscope.team_member_role tmr
                      JOIN crewscope.team_member tm
                        ON tm.organization_id = tmr.organization_id
                       AND tm.team_id = tmr.team_id AND tm.id = tmr.team_member_id
                     WHERE tmr.organization_id = :organizationId
                       AND tm.user_principal_id = :targetPrincipalId
                    """, parameters);
            case "NOTIFICATION_PREFERENCE" -> count(jdbc, """
                    SELECT COUNT(*) FROM crewscope.notification_preference np
                      JOIN crewscope.team_member tm
                        ON tm.organization_id = np.organization_id
                       AND tm.team_id = np.team_id AND tm.id = np.member_id
                     WHERE np.organization_id = :organizationId
                       AND tm.user_principal_id = :targetPrincipalId
                    """, parameters);
            case "INBOX_DISPOSITION" -> count(jdbc, """
                    SELECT COUNT(*) FROM crewscope.inbox_disposition d
                      JOIN crewscope.team_member tm
                        ON tm.organization_id = d.organization_id
                       AND tm.team_id = d.team_id AND tm.id = d.member_id
                     WHERE d.organization_id = :organizationId
                       AND tm.user_principal_id = :targetPrincipalId
                    """, parameters);
            case "CONVERSATION_PARTICIPANT" -> count(jdbc, """
                    SELECT COUNT(*) FROM crewscope.conversation_participant
                     WHERE organization_id = :organizationId
                       AND principal_id = :targetPrincipalId
                    """, parameters);
            case "COMMAND_RESULT" -> count(jdbc, """
                    SELECT COUNT(*) FROM crewscope.command_result
                     WHERE organization_id = :organizationId
                       AND actor_id = :targetPrincipalId
                    """, parameters);
            case "TEAM_INVITATION" -> count(jdbc, """
                    SELECT COUNT(*) FROM crewscope.team_invitation
                     WHERE organization_id = :organizationId
                       AND (invited_by_principal_id = :targetPrincipalId
                            OR accepted_member_id IN (SELECT id FROM crewscope.team_member
                                                       WHERE organization_id = :organizationId
                                                         AND user_principal_id = :targetPrincipalId))
                    """, parameters);
            case "ORGANIZATION_ROLE_GRANT" -> count(jdbc, """
                    SELECT COUNT(*) FROM crewscope.organization_role_grant
                     WHERE organization_id = :organizationId
                       AND principal_id = :targetPrincipalId
                    """, parameters);
            case "TEAM_MEMBER" -> count(jdbc, """
                    SELECT COUNT(*) FROM crewscope.team_member
                     WHERE organization_id = :organizationId
                       AND user_principal_id = :targetPrincipalId
                       AND status IN ('INVITED', 'ACTIVE', 'SUSPENDED')
                    """, parameters);
            default -> throw new IllegalArgumentException(
                    "Unknown DELETE_DERIVED_ROWS target: " + targetRef);
        };
    }

    private int executeDerivedRows(
            OrganizationId organizationId,
            PrincipalId targetPrincipalId,
            String targetRef,
            MapSqlParameterSource parameters) {
        return switch (targetRef) {
            case "TEAM_MEMBER_ROLE" -> jdbc.update("""
                    DELETE FROM crewscope.team_member_role tmr
                     USING crewscope.team_member tm
                     WHERE tm.organization_id = tmr.organization_id
                       AND tm.team_id = tmr.team_id AND tm.id = tmr.team_member_id
                       AND tmr.organization_id = :organizationId
                       AND tm.user_principal_id = :targetPrincipalId
                    """, parameters);
            case "NOTIFICATION_PREFERENCE" -> jdbc.update("""
                    DELETE FROM crewscope.notification_preference np
                     USING crewscope.team_member tm
                     WHERE tm.organization_id = np.organization_id
                       AND tm.team_id = np.team_id AND tm.id = np.member_id
                       AND np.organization_id = :organizationId
                       AND tm.user_principal_id = :targetPrincipalId
                    """, parameters);
            case "INBOX_DISPOSITION" -> jdbc.update("""
                    DELETE FROM crewscope.inbox_disposition d
                     USING crewscope.team_member tm
                     WHERE tm.organization_id = d.organization_id
                       AND tm.team_id = d.team_id AND tm.id = d.member_id
                       AND d.organization_id = :organizationId
                       AND tm.user_principal_id = :targetPrincipalId
                    """, parameters);
            case "CONVERSATION_PARTICIPANT" -> jdbc.update("""
                    DELETE FROM crewscope.conversation_participant
                     WHERE organization_id = :organizationId
                       AND principal_id = :targetPrincipalId
                    """, parameters);
            case "COMMAND_RESULT" -> jdbc.update("""
                    DELETE FROM crewscope.command_result
                     WHERE organization_id = :organizationId
                       AND actor_id = :targetPrincipalId
                    """, parameters);
            case "TEAM_INVITATION" -> jdbc.update("""
                    DELETE FROM crewscope.team_invitation
                     WHERE organization_id = :organizationId
                       AND (invited_by_principal_id = :targetPrincipalId
                            OR accepted_member_id IN (SELECT id FROM crewscope.team_member
                                                       WHERE organization_id = :organizationId
                                                         AND user_principal_id = :targetPrincipalId))
                    """, parameters);
            case "ORGANIZATION_ROLE_GRANT" -> jdbc.update("""
                    DELETE FROM crewscope.organization_role_grant
                     WHERE organization_id = :organizationId
                       AND principal_id = :targetPrincipalId
                    """, parameters);
            // M9b soft removal: the row stays, ownership references keep resolving, and a
            // repeated run converges because the status predicate no longer matches.
            case "TEAM_MEMBER" -> jdbc.update("""
                    UPDATE crewscope.team_member
                       SET status = 'REMOVED', updated_at = CURRENT_TIMESTAMP
                     WHERE organization_id = :organizationId
                       AND user_principal_id = :targetPrincipalId
                       AND status IN ('INVITED', 'ACTIVE', 'SUSPENDED')
                    """, parameters);
            default -> throw new IllegalArgumentException(
                    "Unknown DELETE_DERIVED_ROWS target: " + targetRef);
        };
    }

    /**
     * Walks the organization's artifact descriptors in bounded pages, tombstoning the target's
     * still-active objects. Already-tombstoned objects are invisible to the predicate, so a
     * re-run settles as a no-op (or CONFLICT at the item level, per the backup statement).
     */
    private long countTargetArtifacts(OrganizationId organizationId, PrincipalId targetPrincipalId) {
        long total = 0;
        for (List<ArtifactDescriptor> page = pageArtifacts(organizationId, null);
                !page.isEmpty();
                page = pageArtifacts(organizationId,
                        page.get(page.size() - 1).artifactId())) {
            total += page.stream()
                    .filter(descriptor -> isTargetArtifact(descriptor, targetPrincipalId))
                    .count();
        }
        return total;
    }

    private int tombstoneTargetArtifacts(
            OrganizationId organizationId, PrincipalId targetPrincipalId) {
        ArtifactMutationContext mutation =
                new ArtifactMutationContext(organizationId, targetPrincipalId);
        int tombstoned = 0;
        for (List<ArtifactDescriptor> page = pageArtifacts(organizationId, null);
                !page.isEmpty();
                page = pageArtifacts(organizationId,
                        page.get(page.size() - 1).artifactId())) {
            for (ArtifactDescriptor descriptor : page) {
                if (isTargetArtifact(descriptor, targetPrincipalId)) {
                    artifacts.tombstone(
                            descriptor.artifactId(),
                            mutation,
                            ArtifactTombstoneReason.SECURITY_POLICY,
                            Optional.of("account-deletion governance tombstone"));
                    tombstoned++;
                }
            }
        }
        return tombstoned;
    }

    private static boolean isTargetArtifact(ArtifactDescriptor descriptor, PrincipalId target) {
        return descriptor.producer().principalId().equals(target)
                && descriptor.tombstone().isEmpty();
    }

    private List<ArtifactDescriptor> pageArtifacts(
            OrganizationId organizationId, ArtifactId after) {
        return artifacts.listDescriptorsByOrganization(
                organizationId, Optional.ofNullable(after), ARTIFACT_PAGE);
    }

    private static MapSqlParameterSource parameters(
            OrganizationId organizationId, PrincipalId targetPrincipalId, String targetRef) {
        return new MapSqlParameterSource()
                .addValue("organizationId", organizationId.value())
                .addValue("targetPrincipalId", targetPrincipalId.value())
                .addValue("targetRef", targetRef);
    }

    private static long count(
            NamedParameterJdbcTemplate jdbc, String sql, MapSqlParameterSource parameters) {
        Long value = jdbc.queryForObject(sql, parameters, Long.class);
        return value == null ? 0L : value;
    }
}
