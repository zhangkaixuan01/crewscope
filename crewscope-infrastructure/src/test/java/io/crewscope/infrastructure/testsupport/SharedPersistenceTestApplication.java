package io.crewscope.infrastructure.testsupport;

import io.crewscope.infrastructure.persistence.command.JdbcCommandReceiptStore;
import io.crewscope.infrastructure.persistence.event.JdbcDomainEventStore;
import io.crewscope.infrastructure.persistence.event.JdbcOutboxRepository;
import io.crewscope.infrastructure.persistence.identity.IdentityPersistenceMapper;
import io.crewscope.infrastructure.persistence.identity.JdbcAccountOrganizationBindingRepositoryAdapter;
import io.crewscope.infrastructure.persistence.identity.JdbcCurrentAccountSnapshotReader;
import io.crewscope.infrastructure.persistence.identity.JdbcLocalCredentialMetadataRepositoryAdapter;
import io.crewscope.infrastructure.persistence.identity.JdbcLoginIdentityRepositoryAdapter;
import io.crewscope.infrastructure.persistence.identity.JdbcUserAccountRepositoryAdapter;
import io.crewscope.infrastructure.persistence.organization.JdbcOrganizationQuotaConfigurationRepositoryAdapter;
import io.crewscope.infrastructure.persistence.organization.JdbcOrganizationQuotaUsageQueryRepositoryAdapter;
import io.crewscope.infrastructure.persistence.organization.JdbcOrganizationRoleGrantRepositoryAdapter;
import io.crewscope.infrastructure.persistence.organization.JdbcOrganizationSettingRepositoryAdapter;
import io.crewscope.infrastructure.persistence.organization.JdbcOrganizationTokenBudgetLedgerAdapter;
import io.crewscope.infrastructure.persistence.taskruntime.JdbcOrganizationTaskIntakeQuotaCounterAdapter;
import io.crewscope.infrastructure.persistence.team.JdbcTeamInvitationRepositoryAdapter;
import io.crewscope.infrastructure.persistence.team.TeamInvitationPersistenceMapper;
import io.crewscope.infrastructure.persistence.team.TeamPersistenceMapper;
import io.crewscope.infrastructure.persistence.team.JpaMemberRoleRepositoryAdapter;
import io.crewscope.infrastructure.persistence.team.JpaPrincipalRepositoryAdapter;
import io.crewscope.infrastructure.persistence.team.JpaTeamMemberRepositoryAdapter;
import io.crewscope.infrastructure.persistence.team.JpaTeamRepositoryAdapter;
import io.crewscope.infrastructure.persistence.team.JpaTeamRoleRepositoryAdapter;
import io.crewscope.infrastructure.persistence.team.JpaWorkspaceRepositoryAdapter;
import io.crewscope.infrastructure.transaction.SpringTransactionExecutor;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Import;

/**
 * One shared boot configuration for the persistence integration tests, so every class joins the
 * same cached Spring TestContext instead of cold-starting a private application.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@EntityScan(basePackages = "io.crewscope.infrastructure.persistence.team")
@Import({
    IdentityPersistenceMapper.class,
    JdbcUserAccountRepositoryAdapter.class,
    JdbcLoginIdentityRepositoryAdapter.class,
    JdbcLocalCredentialMetadataRepositoryAdapter.class,
    JdbcAccountOrganizationBindingRepositoryAdapter.class,
    JdbcCurrentAccountSnapshotReader.class,
    SpringTransactionExecutor.class,
    JdbcOrganizationQuotaConfigurationRepositoryAdapter.class,
    JdbcOrganizationTaskIntakeQuotaCounterAdapter.class,
    JdbcOrganizationTokenBudgetLedgerAdapter.class,
    JdbcOrganizationQuotaUsageQueryRepositoryAdapter.class,
    JdbcOrganizationRoleGrantRepositoryAdapter.class,
    JdbcOrganizationSettingRepositoryAdapter.class,
    TeamInvitationPersistenceMapper.class,
    JdbcTeamInvitationRepositoryAdapter.class,
    TeamPersistenceMapper.class,
    JpaTeamRepositoryAdapter.class,
    JpaTeamMemberRepositoryAdapter.class,
    JpaTeamRoleRepositoryAdapter.class,
    JpaMemberRoleRepositoryAdapter.class,
    JpaPrincipalRepositoryAdapter.class,
    JpaWorkspaceRepositoryAdapter.class,
    JdbcDomainEventStore.class,
    JdbcOutboxRepository.class,
    JdbcCommandReceiptStore.class
})
public class SharedPersistenceTestApplication {}
