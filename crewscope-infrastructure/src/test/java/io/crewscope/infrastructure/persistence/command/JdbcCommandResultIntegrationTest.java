package io.crewscope.infrastructure.persistence.command;

import static org.junit.jupiter.api.Assertions.*;

import io.crewscope.application.command.*;
import io.crewscope.domain.shared.error.IdempotencyConflictException;
import io.crewscope.domain.shared.id.*;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Disposable database only: never reads deployment configuration or connects to product services. */
@Testcontainers(disabledWithoutDocker = true)
class JdbcCommandResultIntegrationTest {
  @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");
  private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-09-22T00:00:00Z");
  private final OrganizationId org = OrganizationId.generate();
  private final PrincipalId actor = PrincipalId.generate();
  private final TeamId team = TeamId.generate();
  private final UUID workspace = UUID.randomUUID();
  private DriverManagerDataSource dataSource;
  private JdbcTemplate jdbc;
  private TransactionTemplate tx;
  private CommandReceiptStore store;

  @BeforeEach void initializeV39() {
    dataSource = new DriverManagerDataSource(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    jdbc = new JdbcTemplate(dataSource);
    // This schema exists only inside this class's fresh, unshared Testcontainer.
    jdbc.execute("DROP SCHEMA IF EXISTS crewscope CASCADE");
    migrate("39");
    jdbc.update("INSERT INTO crewscope.organization (id,name,status) VALUES (?,'Org','ACTIVE')", org.value());
    jdbc.update("INSERT INTO crewscope.team (id,organization_id,name,status) VALUES (?,?,'Team','ACTIVE')",
        team.value(), org.value());
    jdbc.update("""
        INSERT INTO crewscope.workspace (id,organization_id,team_id,workspace_type,name,status)
        VALUES (?,?,?,'TEAM','Workspace','ACTIVE')
        """, workspace, org.value(), team.value());
    jdbc.update("""
        INSERT INTO crewscope.principal (id,organization_id,principal_type,display_name,status)
        VALUES (?,?,'USER','Creator','ACTIVE')
        """, actor.value(), org.value());
    var manager = new DataSourceTransactionManager(dataSource);
    tx = new TransactionTemplate(manager);
    var proxy = new ProxyFactory(new JdbcCommandReceiptStore(jdbc));
    proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
    store = (CommandReceiptStore) proxy.getProxy();
  }

  @Test void durableReadSurvivesNewAdapterAndConnectionsAndReplayHasOneResult() {
    migrate("40");
    var request = request("durable");
    var original = tx.execute(status -> create(request, actor, true));
    var reader = new JdbcCommandReceiptStore(new JdbcTemplate(new DriverManagerDataSource(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())));
    assertEquals(original, reader.findResult(org, request.idempotencyKey(), actor).orElseThrow().receipt());
    assertTrue(reader.findResult(org, request.idempotencyKey(), PrincipalId.generate()).isEmpty());
    assertTrue(reader.findResult(OrganizationId.generate(), request.idempotencyKey(), actor).isEmpty());
    assertEquals(original, tx.execute(status -> create(request, actor, true)));
    assertCounts(1);
  }

  @Test void resultConstraintFailureRollsBackBusinessEventOutboxAndReceipt() {
    migrate("40");
    assertThrows(DataAccessException.class,
        () -> tx.execute(status -> create(request("rollback"), PrincipalId.generate(), true)));
    assertCounts(0);
  }

  @Test void failureAfterSavingResultAlsoRollsBackEverything() {
    migrate("40");
    assertThrows(IllegalStateException.class, () -> tx.execute(status -> {
      create(request("after-result"), actor, true);
      throw new IllegalStateException("simulated failure before commit");
    }));
    assertCounts(0);
  }

  @Test void concurrentSameKeyCreatesOneBusinessFactAndSameReceipt() throws Exception {
    migrate("40");
    var request = request("concurrent");
    var start = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
    try {
      var first = executor.submit(() -> {
        assertTrue(start.await(10, TimeUnit.SECONDS));
        return tx.execute(status -> create(request, actor, true));
      });
      var second = executor.submit(() -> {
        assertTrue(start.await(10, TimeUnit.SECONDS));
        // HTTP retries reserve a fresh internal command/correlation ID but the same intent key/hash.
        return tx.execute(status -> create(request("concurrent"), actor, true));
      });
      start.countDown();
      assertEquals(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
      assertCounts(1);
      var changed = new CommandReservationRequest(org, request.idempotencyKey(), request.commandType(),
          CommandRequestHash.sha256(request.commandType(), "changed"), UUID.randomUUID(),
          UUID.randomUUID(), NOW);
      assertThrows(IdempotencyConflictException.class, () -> tx.execute(status -> store.reserve(changed)));
      assertCounts(1);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test void v39UpgradeDoesNotGuessOrBackfillOldReceiptCoordinates() {
    var legacy = request("legacy");
    var receipt = tx.execute(status -> create(legacy, actor, false));
    migrate("40");
    assertTrue(store.findResult(org, legacy.idempotencyKey(), actor).isEmpty());
    assertEquals(receipt, tx.execute(status -> create(legacy, actor, true)));
    assertEquals(0, count("command_result"));
    assertEquals(1, count("work_project"));
  }

  @Test void resultsAreImmutableAndReceiptIdentityCannotBeMixed() {
    migrate("40");
    var first = request("immutable");
    var second = request("second");
    var third = request("third");
    tx.execute(status -> create(first, actor, true));
    tx.execute(status -> create(second, actor, false));
    tx.execute(status -> create(third, actor, false));
    assertThrows(DataAccessException.class, () -> jdbc.update(
        "UPDATE crewscope.command_result SET resource_version=1 WHERE organization_id=?", org.value()));
    assertThrows(DataAccessException.class, () -> jdbc.update(
        "DELETE FROM crewscope.command_result WHERE organization_id=?", org.value()));
    assertThrows(DataAccessException.class, () -> jdbc.update("""
        INSERT INTO crewscope.command_result
          (organization_id,idempotency_key,command_id,actor_id,command_type,team_id,
           project_id,resource_type,resource_id,resource_version,created_at)
        SELECT organization_id,?,?,actor_id,command_type,team_id,
           project_id,resource_type,resource_id,resource_version,created_at
        FROM crewscope.command_result WHERE organization_id=?
        """, second.idempotencyKey().value(), third.commandId(), org.value()));
    assertEquals(1, count("command_result"));
  }

  @Test void rejectsPendingOrMismatchedReceiptAndRequiresTransaction() {
    migrate("40");
    var request = request("pending");
    var receipt = new CommandReceipt(request.commandId(), UUID.randomUUID(), 0, request.correlationId());
    var result = new CommandResult(org, request.idempotencyKey(), actor, request.commandType(),
        team, Optional.empty(), CommandResult.ResourceType.CONVERSATION, UUID.randomUUID(), 0, receipt, NOW);
    assertThrows(IllegalTransactionStateException.class, () -> store.saveResult(result));
    assertThrows(IllegalStateException.class, () -> tx.execute(status -> {
      store.reserve(request);
      store.saveResult(result);
      return null;
    }));
    assertCounts(0);
    tx.execute(status -> create(request, actor, false));
    assertThrows(IllegalStateException.class, () -> tx.execute(status -> {
      store.saveResult(result); // Correct command id, but not its domain-event receipt.
      return null;
    }));
    assertEquals(0, count("command_result"));
  }

  private CommandReceipt create(CommandReservationRequest request, PrincipalId resultActor, boolean saveResult) {
    var reservation = store.reserve(request);
    if (!reservation.acquired()) return reservation.receipt().orElseThrow();
    WorkProjectId project = WorkProjectId.generate();
    jdbc.update("""
        INSERT INTO crewscope.work_project (id,organization_id,team_id,workspace_id,project_key,name)
        VALUES (?,?,?,? ,?,'Project')
        """, project.value(), org.value(), team.value(), workspace,
        "P" + project.value().toString().replace("-", "").substring(0, 8).toUpperCase(java.util.Locale.ROOT));
    UUID event = UUID.randomUUID();
    jdbc.update("""
        INSERT INTO crewscope.domain_event
          (event_id,event_type,schema_version,organization_id,team_id,workspace_id,subject_type,
           subject_id,actor_type,actor_id,correlation_id,idempotency_key,occurred_at,payload,aggregate_version)
        VALUES (?,'WORK_PROJECT_CREATED','1',?,?,?,'WORK_PROJECT',?,'USER',?,?,?,?,'{}'::jsonb,0)
        """, event, org.value(), team.value(), workspace, project.value(), actor.value(),
        request.correlationId(), request.idempotencyKey().value(), NOW.toOffsetDateTime());
    jdbc.update("""
        INSERT INTO crewscope.outbox_event (id,domain_event_id,topic,partition_key,delivery_status)
        VALUES (?,?,'domain-events',?,'PENDING')
        """, UUID.randomUUID(), event, project.toString());
    var receipt = new CommandReceipt(request.commandId(), event, 0, request.correlationId());
    store.complete(org, request.idempotencyKey(), receipt, NOW);
    if (saveResult) store.saveResult(new CommandResult(org, request.idempotencyKey(), resultActor,
        request.commandType(), team, Optional.of(project), CommandResult.ResourceType.WORK_PROJECT,
        project.value(), 0, receipt, NOW));
    return receipt;
  }

  private CommandReservationRequest request(String key) {
    return new CommandReservationRequest(org, new IdempotencyKey(key), "CREATE_WORK_PROJECT",
        CommandRequestHash.sha256("CREATE_WORK_PROJECT", actor.toString(), "Project"),
        UUID.randomUUID(), UUID.randomUUID(), NOW);
  }

  private void migrate(String target) {
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
        .schemas("crewscope").defaultSchema("crewscope").target(target).load().migrate();
  }

  private int count(String table) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM crewscope." + table, Integer.class);
  }

  private void assertCounts(int expected) {
    for (String table : List.of("work_project", "domain_event", "outbox_event", "command_receipt", "command_result"))
      assertEquals(expected, count(table), table);
  }
}
