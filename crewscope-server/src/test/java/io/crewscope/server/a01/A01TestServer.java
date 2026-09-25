package io.crewscope.server.a01;

import io.crewscope.application.command.*;
import io.crewscope.application.conversation.*;
import io.crewscope.application.event.*;
import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.team.*;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.workitem.*;
import io.crewscope.domain.conversation.ConversationVisibilityPolicy;
import io.crewscope.domain.conversation.*;
import io.crewscope.infrastructure.persistence.provider.*;
import io.crewscope.application.provider.BuiltInProviderInitializationService;
import io.crewscope.domain.identity.*;
import io.crewscope.domain.shared.id.*;
import io.crewscope.domain.team.*;
import io.crewscope.domain.workitem.*;
import java.util.Optional;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.*;
import io.crewscope.infrastructure.persistence.command.JdbcCommandReceiptStore;
import io.crewscope.infrastructure.persistence.conversation.*;
import io.crewscope.infrastructure.persistence.event.*;
import io.crewscope.infrastructure.persistence.responsibility.*;
import io.crewscope.infrastructure.persistence.team.*;
import io.crewscope.infrastructure.persistence.workitem.*;
import io.crewscope.infrastructure.transaction.SpringTransactionExecutor;
import io.crewscope.server.api.*;
import io.crewscope.server.config.application.WorkItemApplicationConfiguration;
import io.crewscope.server.config.application.ConversationApplicationConfiguration;
import io.crewscope.server.config.application.ProviderApplicationConfiguration;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.reactive.config.EnableWebFlux;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.adapter.WebHttpHandlerBuilder;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServer;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Test-classpath-only server: real controllers/services/Postgres, no product configuration,
 * workers, providers, Redis, credentials or deployment services. The sole identity substitution
 * maps a test actor header to a persisted Principal; membership/permissions are real. */
public final class A01TestServer {
  public static AnnotationConfigApplicationContext context() {
    return new AnnotationConfigApplicationContext(Config.class);
  }
  public static void main(String[] args) throws Exception {
    var context = context();
    var jdbc = context.getBean(JdbcTemplate.class);
    if (jdbc.queryForObject("SELECT count(*) FROM crewscope.organization", Integer.class) == 0) seed(context);
    System.out.println("A01_SCOPE=" + context.getBean(ObjectMapper.class).writeValueAsString(Map.of(
        "organizationId", ORG.toString(), "actor", ACTOR.toString(), "otherActor", OTHER.toString(),
        "teamId", jdbc.queryForObject("SELECT id::text FROM crewscope.team LIMIT 1", String.class),
        "projectId", jdbc.queryForObject("SELECT id::text FROM crewscope.work_project WHERE project_key='CRW'", String.class),
        "intentId", jdbc.queryForObject("SELECT id::text FROM crewscope.task_intent LIMIT 1", String.class),
        "intentConversationId", jdbc.queryForObject("SELECT conversation_id::text FROM crewscope.task_intent LIMIT 1", String.class))));
    var server = HttpServer.create().host("127.0.0.1").port(0)
        .handle(new ReactorHttpHandlerAdapter(WebHttpHandlerBuilder.applicationContext(context).build())).bindNow();
    Runtime.getRuntime().addShutdownHook(new Thread(() -> { server.disposeNow(); context.close(); }));
    System.out.println("A01_READY=" + server.port());
    System.out.flush();
    server.onDispose().block();
  }
  static final OrganizationId ORG = OrganizationId.from("0198a475-0831-7000-8000-000000000001");
  static final PrincipalId ACTOR = PrincipalId.from("0198a475-0831-7000-8000-000000000002");
  static final PrincipalId OTHER = PrincipalId.from("0198a475-0831-7000-8000-000000000003");
  static void seed(AnnotationConfigApplicationContext c) {
    c.getBean(TransactionExecutor.class).required(() -> {
      var now = UtcTimestamp.from(java.time.Instant.now());
      c.getBean(JdbcTemplate.class).update("INSERT INTO crewscope.organization(id,name,status) VALUES (?,'A01 disposable test','ACTIVE')", ORG.value());
      var actor = Principal.create(ACTOR, PrincipalScope.organization(ORG), PrincipalType.USER,
          Optional.empty(), "Test owner", Optional.empty(), PrincipalVisibility.ORGANIZATION, now);
      var other = Principal.create(OTHER, PrincipalScope.organization(ORG), PrincipalType.USER,
          Optional.empty(), "Test member", Optional.empty(), PrincipalVisibility.ORGANIZATION, now);
      var principals = c.getBean(PrincipalRepository.class);
      principals.createLocalUser(actor); principals.createLocalUser(other);
      var init = TeamInitialization.create(actor, "Test team", now);
      c.getBean(TeamRepository.class).create(init.team());
      c.getBean(WorkspaceRepository.class).create(init.defaultWorkspace());
      c.getBean(TeamMemberRepository.class).create(init.ownerMember());
      c.getBean(TeamRoleRepository.class).createAll(init.builtInRoles());
      c.getBean(MemberRoleRepository.class).create(init.ownerRole());
      c.getBean(DefaultPersonalAgentRepository.class).initializeIfAbsent(init.ownerPersonalAgent());
      var member = TeamMember.join(TeamMemberId.generate(), init.ownerMember().scope(), other, TeamJoinMethod.BOOTSTRAP, now);
      c.getBean(TeamMemberRepository.class).create(member);
      var role = init.builtInRoles().stream().filter(value -> value.isBuiltIn(BuiltInTeamRole.MEMBER)).findFirst().orElseThrow();
      c.getBean(MemberRoleRepository.class).create(MemberRole.grant(MemberRoleId.generate(), member, role,
          RoleScope.team(), actor.id(), now, now, Optional.empty()));
      var project = c.getBean(WorkProjectRepository.class).create(WorkProject.create(WorkProjectId.generate(), new WorkProjectKey("CRW"),
          "Test project", init.team(), init.defaultWorkspace(), actor, now));
      c.getBean(BuiltInProviderInitializationService.class).initialize(init.team(), init.defaultWorkspace(), actor);
      var conversation = PersonalConversationInitialization.start(ConversationId.generate(), init.defaultWorkspace(),
          init.ownerMember(), actor, init.ownerPersonalAgent(), "Seed intent conversation", ConversationVisibility.PRIVATE, now);
      c.getBean(ConversationRepository.class).create(conversation.conversation());
      c.getBean(ConversationParticipantRepository.class).create(conversation.ownerParticipant());
      c.getBean(ConversationParticipantRepository.class).create(conversation.agentParticipant());
      var proposal = TaskIntentProposal.create(conversation.conversation(), project, "Confirm alongside ordinary creation",
          java.util.List.of("No number collision"), TaskIntentCandidate.user(actor, init.ownerMember()), Optional.empty(), Optional.empty());
      var draft = TaskIntent.draft(TaskIntentId.generate(), conversation.conversation(), conversation.agentParticipant(),
          init.ownerPersonalAgent().agentPrincipal(), proposal, now);
      c.getBean(TaskIntentRepository.class).create(draft);
      c.getBean(TaskIntentRepository.class).update(draft.markReady(0, init.ownerPersonalAgent().agentPrincipal(), now));
      return null;
    });
  }

  @Configuration(proxyBeanMethods = false)
  @EnableTransactionManagement
  @EnableWebFlux
  @Import({TeamPersistenceMapper.class, WorkPersistenceMapper.class, WorkItemEntityMapper.class,
      ResponsibilityPersistenceMapper.class, ConversationPersistenceMapper.class,
      JpaPrincipalRepositoryAdapter.class, JpaTeamRepositoryAdapter.class, JpaWorkspaceRepositoryAdapter.class,
      JpaTeamMemberRepositoryAdapter.class, JpaTeamRoleRepositoryAdapter.class, JpaMemberRoleRepositoryAdapter.class,
      JpaAgentProfileRepositoryAdapter.class, JpaWorkProjectRepositoryAdapter.class, JpaWorkItemRepositoryAdapter.class,
      JpaWorkItemCommentRepositoryAdapter.class, JpaWorkItemResourceLinkRepositoryAdapter.class,
      JpaResponsibilityAssignmentRepositoryAdapter.class, JpaConversationRepositoryAdapter.class,
      JdbcConversationEventRepository.class, JdbcWorkItemTimelineRepository.class,
      JdbcDomainEventStore.class, JdbcOutboxRepository.class, JdbcCommandReceiptStore.class,
      SpringTransactionExecutor.class, WorkItemApplicationConfiguration.class,
      ConversationApplicationConfiguration.class, ProviderApplicationConfiguration.class,
      JpaProviderRepositoryAdapter.class, ProviderPersistenceMapper.class, TaskIntentController.class,
      WorkItemController.class, WorkProjectController.class, WorkItemQueryController.class,
      ConversationController.class, CommandResultController.class, ApiExceptionHandler.class})
  public static class Config {
    @Bean DataSource dataSource() {
      // Only explicitly supplied disposable Testcontainer coordinates are accepted.
      String url = System.getenv("A01_TEST_JDBC");
      if (url == null || !url.startsWith("jdbc:postgresql://localhost:")) throw new IllegalStateException("Disposable A01 database required");
      return new DriverManagerDataSource(url, System.getenv("A01_TEST_USER"), System.getenv("A01_TEST_PASSWORD"));
    }
    @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
      var factory = new LocalContainerEntityManagerFactoryBean();
      factory.setDataSource(dataSource);
      factory.setPackagesToScan("io.crewscope.infrastructure.persistence");
      factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
      factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate", "hibernate.default_schema", "crewscope"));
      return factory;
    }
    @Bean EntityManager entityManager(EntityManagerFactory factory) { return SharedEntityManagerCreator.createSharedEntityManager(factory); }
    @Bean PlatformTransactionManager transactionManager(EntityManagerFactory factory) { return new JpaTransactionManager(factory); }
    @Bean JdbcTemplate jdbcTemplate(DataSource dataSource) { return new JdbcTemplate(dataSource); }
    @Bean NamedParameterJdbcTemplate namedJdbc(JdbcTemplate jdbc) { return new NamedParameterJdbcTemplate(jdbc); }
    @Bean ObjectMapper objectMapper() { return JsonMapper.builder().findAndAddModules().build(); }
    @Bean TimeProvider timeProvider() { return () -> UtcTimestamp.from(java.time.Instant.now()); }
    @Bean WebFilter testActor() {
      return (exchange, chain) -> {
        String actor = exchange.getRequest().getHeaders().getFirst("X-A01-Test-Actor");
        if (actor == null) return chain.filter(exchange);
        var auth = new UsernamePasswordAuthenticationToken(actor, "test-only");
        return chain.filter(exchange.mutate().principal(Mono.just(auth)).build());
      };
    }
    @Bean TeamRequestIdentityResolver identity(PrincipalRepository principals) {
      return (authentication, org, correlation) -> Mono.fromCallable(() ->
          new TeamAccessContext(principals.findById(org, PrincipalId.from(authentication.getName())).orElseThrow(), false));
    }
    @Bean CommandResultQueryService results(CommandResultStore results, WorkItemAccessPolicy access,
        ConversationApplicationService conversations, TeamMemberRepository members,
        TransactionExecutor transactions) {
      return new CommandResultQueryService(results, access, conversations, members, transactions);
    }
  }
}
