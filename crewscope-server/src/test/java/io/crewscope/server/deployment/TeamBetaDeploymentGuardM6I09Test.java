package io.crewscope.server.deployment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** Locks down API/Worker role separation and production configuration failure closure. */
class TeamBetaDeploymentGuardM6I09Test {

    @Test
    void acceptsExactApiAndWorkerRoles() {
        assertDoesNotThrow(() -> TeamBetaDeploymentGuard.validate(environment(false)));
        assertDoesNotThrow(() -> TeamBetaDeploymentGuard.validate(environment(true)));
    }

    @Test
    void rejectsCombinedRoleAndDevelopmentSecrets() {
        MockEnvironment combined = environment(false)
                .withProperty("crewscope.runtime.execution-profile", "all");
        IllegalStateException combinedFailure = assertThrows(
                IllegalStateException.class,
                () -> TeamBetaDeploymentGuard.validate(combined));
        assertTrue(combinedFailure.getMessage().contains("server or worker"));

        MockEnvironment developmentSecret = environment(true)
                .withProperty(
                        "crewscope.coding.diff.cursor-secret",
                        "crewscope-development-diff-cursor-key-v1");
        IllegalStateException secretFailure = assertThrows(
                IllegalStateException.class,
                () -> TeamBetaDeploymentGuard.validate(developmentSecret));
        assertTrue(secretFailure.getMessage().contains("development value"));
    }

    @Test
    void rejectsRoleThatOwnsFlywayAndWorkerSchedulersTogether() {
        MockEnvironment workerWithFlyway = environment(true)
                .withProperty("spring.flyway.enabled", "true");
        IllegalStateException migrationFailure = assertThrows(
                IllegalStateException.class,
                () -> TeamBetaDeploymentGuard.validate(workerWithFlyway));
        assertTrue(migrationFailure.getMessage().contains("spring.flyway.enabled must be false"));

        MockEnvironment apiWithOutbox = environment(false)
                .withProperty("crewscope.outbox.enabled", "true");
        IllegalStateException schedulerFailure = assertThrows(
                IllegalStateException.class,
                () -> TeamBetaDeploymentGuard.validate(apiWithOutbox));
        assertTrue(schedulerFailure.getMessage().contains("crewscope.outbox.enabled must be false"));
    }

    private static MockEnvironment environment(boolean worker) {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("crewscope.runtime.execution-profile", worker ? "worker" : "server");
        properties.put("crewscope.runtime.redis.ownership-scope", worker ? "worker" : "server");
        properties.put("crewscope.deployment.config-source", "external");
        properties.put("crewscope.deployment.secret-source", "external-file");
        properties.put("spring.config.import", "configtree:/run/secrets/");
        properties.put("spring.datasource.password", "database-password-with-32-bytes");
        properties.put("crewscope.security.bootstrap.password", "bootstrap-password-with-32-bytes");
        properties.put(
                "crewscope.credential.encryption.keys",
                "v1=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        properties.put(
                "crewscope.team-activity-realtime.keys.v1",
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        properties.put(
                "crewscope.security.task-token.keys.v1",
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        properties.put(
                "crewscope.coding.diff.cursor-secret",
                "a-team-beta-diff-cursor-secret-with-more-than-32-bytes");
        properties.put(
                "crewscope.runtime.redis.url",
                "redis://default:strong-password@redis:6379");
        properties.put("crewscope.security.task-token.enabled", "true");
        properties.put("management.tracing.export.otlp.enabled", "true");
        properties.put(
                "management.opentelemetry.tracing.export.otlp.endpoint",
                "http://otel-collector:4318/v1/traces");
        properties.put("spring.flyway.enabled", Boolean.toString(!worker));
        properties.put("crewscope.outbox.enabled", Boolean.toString(worker));
        properties.put("crewscope.action.worker.enabled", Boolean.toString(worker));
        properties.put("crewscope.action.reconciliation.enabled", Boolean.toString(worker));
        properties.put(
                "crewscope.action.reconciliation.startup-enabled", Boolean.toString(worker));
        properties.put("crewscope.notification.worker.enabled", Boolean.toString(worker));
        properties.put(
                "crewscope.notification.worker.reconciliation-enabled",
                Boolean.toString(worker));
        properties.put(
                "crewscope.notification.worker.redelivery-enabled",
                Boolean.toString(worker));
        properties.put("crewscope.projection.supervisor.enabled", Boolean.toString(worker));
        properties.put(
                "crewscope.team-activity-realtime.enabled", Boolean.toString(!worker));
        properties.put("crewscope.artifact.filesystem.root", "/srv/crewscope/artifacts");
        properties.put(
                "crewscope.coding.repository.managed-root", "/srv/crewscope/repositories");
        properties.put("crewscope.coding.worktree.root", "/srv/crewscope/worktrees");
        properties.put(
                "crewscope.coding.worktree.lock-root", "/srv/crewscope/worktree-locks");
        properties.put(
                "crewscope.runtime.registry.organization-id",
                "0198a475-0831-7000-8000-000000000001");
        properties.put(
                "crewscope.runtime.registry.actor-principal-id",
                "0198a475-0831-7000-8000-000000000002");
        if (worker) {
            properties.put("crewscope.runtime.registry.worker.stable-key", "worker-1");
            properties.put("crewscope.outbox.worker-id", "outbox-1");
            properties.put("crewscope.projection.supervisor.instance-id", "projection-1");
        }
        MockEnvironment environment = new MockEnvironment();
        properties.forEach(environment::withProperty);
        return environment;
    }
}
