package io.crewscope.infrastructure.testsupport;

import io.crewscope.application.workgraph.WorkGraphQueryService;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.infrastructure.persistence.command.JdbcCommandReceiptStore;
import io.crewscope.infrastructure.persistence.conversation.ConversationPersistenceMapper;
import io.crewscope.infrastructure.persistence.conversation.JdbcConversationEventRepository;
import io.crewscope.infrastructure.persistence.conversation.JpaAgentRuntimeSessionRepositoryAdapter;
import io.crewscope.infrastructure.persistence.conversation.JpaConversationRepositoryAdapter;
import io.crewscope.infrastructure.persistence.event.JdbcDomainEventStore;
import io.crewscope.infrastructure.persistence.event.JdbcOutboxRepository;
import io.crewscope.infrastructure.persistence.identity.IdentityPersistenceMapper;
import io.crewscope.infrastructure.persistence.identity.JdbcAccountOrganizationBindingRepositoryAdapter;
import io.crewscope.infrastructure.persistence.identity.JdbcLocalCredentialStoreAdapter;
import io.crewscope.infrastructure.persistence.identity.JdbcLoginIdentityRepositoryAdapter;
import io.crewscope.infrastructure.persistence.identity.JdbcUserAccountRepositoryAdapter;
import io.crewscope.infrastructure.persistence.inbox.JdbcInboxRepositoryAdapter;
import io.crewscope.infrastructure.persistence.organization.JdbcOrganizationMemberDirectoryRepositoryAdapter;
import io.crewscope.infrastructure.persistence.organization.JdbcOrganizationRepositoryAdapter;
import io.crewscope.infrastructure.persistence.principal.JdbcPrincipalDirectoryRepositoryAdapter;
import io.crewscope.infrastructure.persistence.provider.JpaProviderRepositoryAdapter;
import io.crewscope.infrastructure.persistence.provider.ProviderPersistenceMapper;
import io.crewscope.infrastructure.persistence.responsibility.JpaResponsibilityAssignmentRepositoryAdapter;
import io.crewscope.infrastructure.persistence.responsibility.ResponsibilityPersistenceMapper;
import io.crewscope.infrastructure.persistence.taskruntime.JdbcClaimQuotaRepositoryAdapter;
import io.crewscope.infrastructure.persistence.taskruntime.JdbcExecutionLeaseRepositoryAdapter;
import io.crewscope.infrastructure.persistence.taskruntime.JdbcTaskEventRepository;
import io.crewscope.infrastructure.persistence.taskruntime.JdbcTaskExecutionQueueRepositoryAdapter;
import io.crewscope.infrastructure.persistence.taskruntime.JpaPolicySnapshotRepositoryAdapter;
import io.crewscope.infrastructure.persistence.taskruntime.JpaSafetyEnforcementOverlayRepositoryAdapter;
import io.crewscope.infrastructure.persistence.taskruntime.JpaTaskRuntimeRepositoryAdapter;
import io.crewscope.infrastructure.persistence.taskruntime.TaskRuntimeExtendedPersistenceMapper;
import io.crewscope.infrastructure.persistence.taskruntime.TaskRuntimePackageConfiguration;
import io.crewscope.infrastructure.persistence.taskruntime.TaskRuntimePersistenceMapper;
import io.crewscope.infrastructure.persistence.team.JdbcTeamInvitationRepositoryAdapter;
import io.crewscope.infrastructure.persistence.team.TeamInvitationPersistenceMapper;
import io.crewscope.infrastructure.persistence.team.JpaAgentProfileRepositoryAdapter;
import io.crewscope.infrastructure.persistence.team.JpaMemberRoleRepositoryAdapter;
import io.crewscope.infrastructure.persistence.team.JpaPrincipalRepositoryAdapter;
import io.crewscope.infrastructure.persistence.team.JpaTeamMemberRepositoryAdapter;
import io.crewscope.infrastructure.persistence.team.JpaTeamRepositoryAdapter;
import io.crewscope.infrastructure.persistence.team.JpaTeamRoleRepositoryAdapter;
import io.crewscope.infrastructure.persistence.team.TeamPersistenceMapper;
import io.crewscope.infrastructure.persistence.team.JpaWorkspaceRepositoryAdapter;
import io.crewscope.infrastructure.persistence.workdesk.JdbcWorkDeskRepositoryAdapter;
import io.crewscope.infrastructure.persistence.workgraph.JdbcWorkItemRelationGraphAdapter;
import io.crewscope.infrastructure.persistence.workgraph.JpaWorkItemRelationRepositoryAdapter;
import io.crewscope.infrastructure.persistence.workitem.JdbcWorkItemSummaryRepositoryAdapter;
import io.crewscope.infrastructure.persistence.workitem.JdbcWorkItemTimelineRepository;
import io.crewscope.infrastructure.persistence.workitem.JpaWorkItemCommentRepositoryAdapter;
import io.crewscope.infrastructure.persistence.workitem.JpaWorkItemRepositoryAdapter;
import io.crewscope.infrastructure.persistence.workitem.JpaWorkItemResourceLinkRepositoryAdapter;
import io.crewscope.infrastructure.persistence.workitem.JpaWorkProjectRepositoryAdapter;
import io.crewscope.infrastructure.persistence.workitem.WorkItemEntityMapper;
import io.crewscope.infrastructure.persistence.workitem.WorkPersistenceMapper;
import io.crewscope.infrastructure.transaction.PostgresAuthoritativeTimeProvider;
import io.crewscope.infrastructure.transaction.SpringTransactionExecutor;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Import;

/**
 * One shared boot configuration for the infrastructure JPA integration tests, so every class
 * joins the same cached Spring TestContext instead of cold-starting a private application.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@EntityScan(basePackages = "io.crewscope.infrastructure.persistence")
@Import({
    TeamPersistenceMapper.class,
    JpaTeamRepositoryAdapter.class,
    JpaWorkspaceRepositoryAdapter.class,
    JpaTeamMemberRepositoryAdapter.class,
    JpaTeamRoleRepositoryAdapter.class,
    JpaMemberRoleRepositoryAdapter.class,
    JpaPrincipalRepositoryAdapter.class,
    JpaAgentProfileRepositoryAdapter.class,
    JdbcDomainEventStore.class,
    JdbcOutboxRepository.class,
    JdbcCommandReceiptStore.class,
    WorkPersistenceMapper.class,
    WorkItemEntityMapper.class,
    JpaWorkProjectRepositoryAdapter.class,
    JpaWorkItemRepositoryAdapter.class,
    JpaWorkItemCommentRepositoryAdapter.class,
    JpaWorkItemResourceLinkRepositoryAdapter.class,
    JdbcWorkItemTimelineRepository.class,
    ResponsibilityPersistenceMapper.class,
    JpaResponsibilityAssignmentRepositoryAdapter.class,
    ProviderPersistenceMapper.class,
    JpaProviderRepositoryAdapter.class,
    IdentityPersistenceMapper.class,
    JdbcUserAccountRepositoryAdapter.class,
    JdbcLoginIdentityRepositoryAdapter.class,
    JdbcAccountOrganizationBindingRepositoryAdapter.class,
    SpringTransactionExecutor.class,
    JdbcLocalCredentialStoreAdapter.class,
    TeamInvitationPersistenceMapper.class,
    JdbcTeamInvitationRepositoryAdapter.class,
    JdbcInboxRepositoryAdapter.class,
    JpaConversationRepositoryAdapter.class,
    JdbcConversationEventRepository.class,
    JpaAgentRuntimeSessionRepositoryAdapter.class,
    ConversationPersistenceMapper.class,
    JdbcOrganizationMemberDirectoryRepositoryAdapter.class,
    JdbcOrganizationRepositoryAdapter.class,
    JdbcPrincipalDirectoryRepositoryAdapter.class,
    TaskRuntimePersistenceMapper.class,
    TaskRuntimePackageConfiguration.class,
    TaskRuntimeExtendedPersistenceMapper.class,
    JpaTaskRuntimeRepositoryAdapter.class,
    JdbcExecutionLeaseRepositoryAdapter.class,
    JdbcTaskEventRepository.class,
    JdbcTaskExecutionQueueRepositoryAdapter.class,
    JdbcClaimQuotaRepositoryAdapter.class,
    JpaPolicySnapshotRepositoryAdapter.class,
    JpaSafetyEnforcementOverlayRepositoryAdapter.class,
    PostgresAuthoritativeTimeProvider.class,
    JdbcWorkDeskRepositoryAdapter.class,
    JpaWorkItemRelationRepositoryAdapter.class,
    JdbcWorkItemRelationGraphAdapter.class,
    WorkItemAccessPolicy.class,
    WorkGraphQueryService.class,
    JdbcWorkItemSummaryRepositoryAdapter.class
})
public class SharedJpaTestApplication {}
