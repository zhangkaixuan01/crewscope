package io.crewscope.server.a01;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Explicit browser gate. Owns and destroys its database; Node owns two independent server JVMs. */
@Testcontainers
@EnabledIfSystemProperty(named = "a01.browser", matches = "true")
class A01BrowserIntegrationTest {
  @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");
  @Test void realBrowserLostResponseReloadAndProcessRestart() throws Exception {
    var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").schemas("crewscope").load().migrate();
    Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath().getParent();
    var log = root.resolve("crewscope-server/target/a01-browser.log");
    var builder = new ProcessBuilder("pnpm", "exec", "playwright", "test", "--config", "playwright.m9b-a01.config.ts")
        .directory(root.resolve("crewscope-web").toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
    builder.environment().put("A01_TEST_JDBC", POSTGRES.getJdbcUrl());
    builder.environment().put("A01_TEST_USER", POSTGRES.getUsername());
    builder.environment().put("A01_TEST_PASSWORD", POSTGRES.getPassword());
    builder.environment().put("A01_TEST_CLASSPATH", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")));
    builder.environment().put("A01_TEST_JAVA", Path.of(System.getProperty("java.home"), "bin", "java").toString());
    var process = builder.start();
    try {
      assertTrue(process.waitFor(180, TimeUnit.SECONDS), "A01 browser gate exceeded its budget");
      assertEquals(0, process.exitValue(), () -> {
        try { return java.nio.file.Files.readString(log); } catch (Exception error) { return log.toString(); }
      });
    } finally { process.destroyForcibly(); }
    var jdbc = new JdbcTemplate(dataSource);
    assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM crewscope.work_item WHERE title='Lost response work'", Integer.class));
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM crewscope.task", Integer.class), "Creating/editing must not start execution");
    assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM crewscope.work_item WHERE title='Confirm alongside ordinary creation'", Integer.class));
    assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM crewscope.command_receipt WHERE idempotency_key='confirm-intent'", Integer.class));
    for (String key : java.util.List.of("project-one", "project-two", "conversation-one", "conversation-two")) {
      assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM crewscope.command_result WHERE idempotency_key=?", Integer.class, key));
      assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM crewscope.command_receipt WHERE idempotency_key=?", Integer.class, key));
    }
    assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM crewscope.domain_event WHERE event_type='WORK_ITEM_CREATED' AND subject_id=(SELECT id FROM crewscope.work_item WHERE title='Lost response work')", Integer.class));
  }
}
