package io.crewscope.server.deployment;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.env.Environment;

/**
 * Fails a Team Beta process before it becomes ready when deployment role, external configuration or
 * Secret boundaries are incomplete. Local development remains governed by the regular defaults.
 */
public final class TeamBetaDeploymentGuard implements SmartInitializingSingleton {

    private static final String DEVELOPMENT_DIFF_SECRET =
            "crewscope-development-diff-cursor-key-v1";

    private final Environment environment;

    public TeamBetaDeploymentGuard(Environment environment) {
        this.environment = Objects.requireNonNull(environment, "environment");
    }

    @Override
    public void afterSingletonsInstantiated() {
        validate(environment);
    }

    static void validate(Environment environment) {
        String role = required(environment, "crewscope.runtime.execution-profile")
                .toLowerCase(Locale.ROOT);
        if (!List.of("server", "worker").contains(role)) {
            throw invalid("crewscope.runtime.execution-profile must be server or worker");
        }
        requireExact(environment, "crewscope.runtime.redis.ownership-scope", role);
        requireExact(environment, "crewscope.deployment.config-source", "external");
        requireExact(environment, "crewscope.deployment.secret-source", "external-file");
        if (!required(environment, "spring.config.import").contains("configtree:")) {
            throw invalid("spring.config.import must load an external configtree Secret directory");
        }

        requireSecret(environment, "spring.datasource.password", 16, "crewscope");
        requireSecret(environment, "crewscope.security.bootstrap.password", 16, "crewscope");
        requireSecret(environment, "crewscope.credential.encryption.keys", 40, "");
        requireSecret(environment, "crewscope.team-activity-realtime.keys.v1", 40, "");
        requireSecret(environment, "crewscope.security.task-token.keys.v1", 40, "");
        requireSecret(
                environment,
                "crewscope.coding.diff.cursor-secret",
                32,
                DEVELOPMENT_DIFF_SECRET);
        String redisUrl = required(environment, "crewscope.runtime.redis.url");
        if (!redisUrl.startsWith("redis://") || !redisUrl.contains("@")
                || redisUrl.contains("localhost") || redisUrl.contains("127.0.0.1")) {
            throw invalid("crewscope.runtime.redis.url must be an authenticated external Redis URL");
        }
        requireBoolean(environment, "crewscope.security.task-token.enabled", true);
        requireBoolean(environment, "management.tracing.export.otlp.enabled", true);
        required(environment, "management.opentelemetry.tracing.export.otlp.endpoint");

        boolean worker = role.equals("worker");
        requireBoolean(environment, "spring.flyway.enabled", !worker);
        requireBoolean(environment, "crewscope.outbox.enabled", worker);
        requireBoolean(environment, "crewscope.action.worker.enabled", worker);
        requireBoolean(environment, "crewscope.action.reconciliation.enabled", worker);
        requireBoolean(environment, "crewscope.action.reconciliation.startup-enabled", worker);
        requireBoolean(environment, "crewscope.notification.worker.enabled", worker);
        requireBoolean(environment, "crewscope.notification.worker.reconciliation-enabled", worker);
        requireBoolean(environment, "crewscope.notification.worker.redelivery-enabled", worker);
        requireBoolean(environment, "crewscope.projection.supervisor.enabled", worker);
        requireBoolean(environment, "crewscope.team-activity-realtime.enabled", !worker);

        requireAbsolutePath(environment, "crewscope.artifact.filesystem.root");
        requireAbsolutePath(environment, "crewscope.coding.repository.managed-root");
        requireAbsolutePath(environment, "crewscope.coding.worktree.root");
        requireAbsolutePath(environment, "crewscope.coding.worktree.lock-root");
        required(environment, "crewscope.runtime.registry.organization-id");
        required(environment, "crewscope.runtime.registry.actor-principal-id");
        if (worker) {
            required(environment, "crewscope.runtime.registry.worker.stable-key");
            required(environment, "crewscope.outbox.worker-id");
            required(environment, "crewscope.projection.supervisor.instance-id");
        }
    }

    private static void requireBoolean(Environment environment, String name, boolean expected) {
        String value = required(environment, name);
        if (!Boolean.toString(expected).equalsIgnoreCase(value)) {
            throw invalid(name + " must be " + expected + " for this Team Beta role");
        }
    }

    private static void requireExact(Environment environment, String name, String expected) {
        if (!expected.equals(required(environment, name))) {
            throw invalid(name + " must be " + expected);
        }
    }

    private static void requireSecret(
            Environment environment, String name, int minimumLength, String forbidden) {
        String value = required(environment, name);
        if (value.length() < minimumLength || (!forbidden.isEmpty() && forbidden.equals(value))) {
            throw invalid(name + " is missing or still uses a development value");
        }
    }

    private static void requireAbsolutePath(Environment environment, String name) {
        String value = required(environment, name);
        try {
            if (!Path.of(value).isAbsolute()) {
                throw invalid(name + " must be an absolute deployment path");
            }
        } catch (InvalidPathException exception) {
            throw invalid(name + " must be a valid absolute deployment path");
        }
    }

    private static String required(Environment environment, String name) {
        String value = environment.getProperty(name);
        if (value == null || value.isBlank()) {
            throw invalid(name + " is required by the Team Beta deployment profile");
        }
        return value.strip();
    }

    private static IllegalStateException invalid(String message) {
        return new IllegalStateException("Team Beta deployment rejected: " + message);
    }
}
