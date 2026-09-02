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
        assertDoesNotThrow(() -> TeamBetaDeploymentGuard.validate(environment(false)
                .withProperty("management.tracing.export.otlp.enabled", "false")));
        assertDoesNotThrow(() -> TeamBetaDeploymentGuard.validate(environment(true)
                .withProperty("management.tracing.export.otlp.enabled", "false")));

        MockEnvironment localDemo = environment(false)
                .withProperty("crewscope.deployment.transport", "local")
                .withProperty("crewscope.security.login-defense.environment", "demo")
                .withProperty("server.reactive.session.cookie.secure", "false");
        assertDoesNotThrow(() -> TeamBetaDeploymentGuard.validate(localDemo));
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

    @Test
    void rejectsSharedMonitoringCredentialAndInsecureHttpsCookie() {
        MockEnvironment sharedCredential = environment(false)
                .withProperty(
                        "crewscope.security.monitoring.password",
                        "bootstrap-password-with-32-bytes");
        IllegalStateException sharedFailure = assertThrows(
                IllegalStateException.class,
                () -> TeamBetaDeploymentGuard.validate(sharedCredential));
        assertTrue(sharedFailure.getMessage().contains("passwords must differ"));

        MockEnvironment insecureCookie = environment(false)
                .withProperty("server.reactive.session.cookie.secure", "false");
        IllegalStateException cookieFailure = assertThrows(
                IllegalStateException.class,
                () -> TeamBetaDeploymentGuard.validate(insecureCookie));
        assertTrue(cookieFailure.getMessage().contains("cookie.secure must be true"));

        MockEnvironment developmentNamespace = environment(false)
                .withProperty("crewscope.security.login-defense.environment", "development");
        IllegalStateException namespaceFailure = assertThrows(
                IllegalStateException.class,
                () -> TeamBetaDeploymentGuard.validate(developmentNamespace));
        assertTrue(namespaceFailure.getMessage().contains("must be team-beta"));
    }

    @Test
    void rejectsMissingAuthenticationDefenseAndUnknownRegistrationMode() {
        MockEnvironment defenseDisabled = environment(false)
                .withProperty("crewscope.security.login-defense.enabled", "false");
        IllegalStateException defenseFailure = assertThrows(
                IllegalStateException.class,
                () -> TeamBetaDeploymentGuard.validate(defenseDisabled));
        assertTrue(defenseFailure.getMessage().contains("login-defense.enabled must be true"));

        MockEnvironment unknownRegistration = environment(false)
                .withProperty("crewscope.registration.mode", "PUBLIC");
        IllegalStateException registrationFailure = assertThrows(
                IllegalStateException.class,
                () -> TeamBetaDeploymentGuard.validate(unknownRegistration));
        assertTrue(registrationFailure.getMessage().contains("OPEN, INVITE_ONLY or DISABLED"));

        MockEnvironment invalidHmac = environment(false)
                .withProperty("crewscope.security.login-defense.hmac-key", "not-base64");
        IllegalStateException hmacFailure = assertThrows(
                IllegalStateException.class,
                () -> TeamBetaDeploymentGuard.validate(invalidHmac));
        assertTrue(hmacFailure.getMessage().contains("valid Base64"));

        MockEnvironment extraActuator = environment(false)
                .withProperty(
                        "management.endpoints.web.exposure.include",
                        "health, info, prometheus, env");
        IllegalStateException actuatorFailure = assertThrows(
                IllegalStateException.class,
                () -> TeamBetaDeploymentGuard.validate(extraActuator));
        assertTrue(actuatorFailure.getMessage().contains("expose exactly"));
    }

    static MockEnvironment environment(boolean worker) {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("crewscope.runtime.execution-profile", worker ? "worker" : "server");
        properties.put("crewscope.runtime.redis.ownership-scope", worker ? "worker" : "server");
        properties.put("crewscope.deployment.config-source", "external");
        properties.put("crewscope.deployment.secret-source", "external-file");
        properties.put("crewscope.deployment.transport", "https");
        properties.put("spring.config.import", "configtree:/run/secrets/");
        properties.put("spring.datasource.password", "database-password-with-32-bytes");
        properties.put("crewscope.security.bootstrap.password", "bootstrap-password-with-32-bytes");
        properties.put("crewscope.security.bootstrap.username", "crewscope-monitor");
        properties.put("crewscope.security.monitoring.username", "crewscope-prometheus");
        properties.put(
                "crewscope.security.monitoring.password", "monitoring-password-with-32-bytes");
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
        properties.put("crewscope.security.mode", worker ? "bootstrap" : "local");
        properties.put("crewscope.security.session.enabled", Boolean.toString(!worker));
        properties.put("crewscope.security.login-defense.enabled", Boolean.toString(!worker));
        properties.put("crewscope.security.login-defense.environment", "team-beta");
        properties.put("crewscope.security.login-defense.hmac-key", base64Secret());
        properties.put("crewscope.security.login-defense.trusted-proxies", "172.30.0.0/24");
        properties.put("crewscope.invitation.token.enabled", Boolean.toString(!worker));
        properties.put("crewscope.invitation.token.hmac-key", base64Secret());
        properties.put(
                "crewscope.security.operator-bootstrap.enabled", Boolean.toString(!worker));
        properties.put(
                "crewscope.security.operator-bootstrap.username", "crewscope-monitor");
        properties.put("crewscope.registration.mode", "INVITE_ONLY");
        properties.put(
                "crewscope.registration.organization-id",
                "0198a475-0831-7000-8000-000000000001");
        properties.put(
                "crewscope.deployment.bootstrap.organization-id",
                "0198a475-0831-7000-8000-000000000001");
        properties.put("server.reactive.session.cookie.name", "CREWSCOPE_SESSION");
        properties.put("server.reactive.session.cookie.path", "/");
        properties.put("server.reactive.session.cookie.http-only", "true");
        properties.put("server.reactive.session.cookie.same-site", "lax");
        properties.put("server.reactive.session.cookie.secure", "true");
        properties.put("logging.structured.format.console", "logstash");
        properties.put("management.endpoint.health.show-details", "never");
        properties.put(
                "management.endpoints.web.exposure.include", "health,info,prometheus");
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
        properties.put("crewscope.github-import.worker.enabled", Boolean.toString(worker));
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
            properties.put("crewscope.github-import.worker.worker-id", "github-import-1");
        }
        MockEnvironment environment = new MockEnvironment();
        properties.forEach(environment::withProperty);
        return environment;
    }

    private static String base64Secret() {
        return "QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=";
    }
}
