package io.crewscope.server.deployment;

import io.crewscope.domain.identity.RegistrationMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
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
        String transport = required(environment, "crewscope.deployment.transport")
                .toLowerCase(Locale.ROOT);
        if (!List.of("https", "local").contains(transport)) {
            throw invalid("crewscope.deployment.transport must be https or local");
        }
        requireExact(
                environment,
                "crewscope.security.login-defense.environment",
                transport.equals("local") ? "demo" : "team-beta");
        if (!required(environment, "spring.config.import").contains("configtree:")) {
            throw invalid("spring.config.import must load an external configtree Secret directory");
        }

        requireSecret(environment, "spring.datasource.password", 16, "crewscope");
        String bootstrapUsername = required(environment, "crewscope.security.bootstrap.username");
        String bootstrapPassword =
                requireSecret(environment, "crewscope.security.bootstrap.password", 16, "crewscope");
        String monitoringUsername = required(environment, "crewscope.security.monitoring.username");
        String monitoringPassword = requireSecret(
                environment,
                "crewscope.security.monitoring.password",
                24,
                "crewscope-monitoring");
        requireDifferent(
                bootstrapUsername,
                monitoringUsername,
                "Operator and monitoring usernames must differ");
        requireDifferent(
                bootstrapPassword,
                monitoringPassword,
                "Operator and monitoring passwords must differ");
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
        requireExplicitBoolean(environment, "management.tracing.export.otlp.enabled");
        required(environment, "management.opentelemetry.tracing.export.otlp.endpoint");
        requireExact(environment, "logging.structured.format.console", "logstash");
        requireExact(environment, "management.endpoint.health.show-details", "never");
        requireExposure(environment, "management.endpoints.web.exposure.include");
        requireRegistrationMode(environment);

        boolean worker = role.equals("worker");
        requireExact(environment, "crewscope.security.mode", worker ? "bootstrap" : "local");
        requireBoolean(environment, "spring.flyway.enabled", !worker);
        requireBoolean(environment, "crewscope.outbox.enabled", worker);
        requireBoolean(environment, "crewscope.action.worker.enabled", worker);
        requireBoolean(environment, "crewscope.action.reconciliation.enabled", worker);
        requireBoolean(environment, "crewscope.action.reconciliation.startup-enabled", worker);
        requireBoolean(environment, "crewscope.notification.worker.enabled", worker);
        requireBoolean(environment, "crewscope.notification.worker.reconciliation-enabled", worker);
        requireBoolean(environment, "crewscope.notification.worker.redelivery-enabled", worker);
        requireBoolean(environment, "crewscope.github-import.worker.enabled", worker);
        requireBoolean(environment, "crewscope.projection.supervisor.enabled", worker);
        requireBoolean(environment, "crewscope.team-activity-realtime.enabled", !worker);
        requireBoolean(environment, "crewscope.security.session.enabled", !worker);
        requireBoolean(environment, "crewscope.security.login-defense.enabled", !worker);
        requireBoolean(environment, "crewscope.invitation.token.enabled", !worker);
        requireBoolean(environment, "crewscope.security.operator-bootstrap.enabled", !worker);
        if (!worker) {
            requireExact(
                    environment,
                    "crewscope.registration.organization-id",
                    required(environment, "crewscope.deployment.bootstrap.organization-id"));
            requireBase64Secret(environment, "crewscope.security.login-defense.hmac-key");
            requireBase64Secret(environment, "crewscope.invitation.token.hmac-key");
            required(environment, "crewscope.security.login-defense.trusted-proxies");
            requireExact(
                    environment,
                    "crewscope.security.operator-bootstrap.username",
                    bootstrapUsername);
            requireCookiePolicy(environment, transport);
        }

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
            required(environment, "crewscope.github-import.worker.worker-id");
        }
    }

    private static void requireExplicitBoolean(Environment environment, String name) {
        String value = required(environment, name);
        if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
            throw invalid(name + " must be explicitly true or false");
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

    private static String requireSecret(
            Environment environment, String name, int minimumLength, String forbidden) {
        String value = required(environment, name);
        if (value.length() < minimumLength || (!forbidden.isEmpty() && forbidden.equals(value))) {
            throw invalid(name + " is missing or still uses a development value");
        }
        return value;
    }

    private static void requireBase64Secret(Environment environment, String name) {
        String encoded = required(environment, name);
        try {
            byte[] decoded = Base64.getDecoder().decode(encoded);
            if (decoded.length < 32) {
                throw invalid(name + " must decode to at least 32 bytes");
            }
        } catch (IllegalArgumentException failure) {
            throw invalid(name + " must be valid Base64 for at least 32 bytes");
        }
    }

    private static void requireDifferent(String left, String right, String message) {
        if (MessageDigest.isEqual(
                left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8))) {
            throw invalid(message);
        }
    }

    private static void requireRegistrationMode(Environment environment) {
        String configured = required(environment, "crewscope.registration.mode");
        try {
            RegistrationMode.valueOf(configured.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException failure) {
            throw invalid("crewscope.registration.mode must be OPEN, INVITE_ONLY or DISABLED");
        }
    }

    private static void requireExposure(Environment environment, String name) {
        List<String> exposed = java.util.Arrays.stream(
                        required(environment, name).toLowerCase(Locale.ROOT).split(","))
                .map(String::strip)
                .toList();
        if (!java.util.Set.copyOf(exposed).equals(
                java.util.Set.of("health", "info", "prometheus"))) {
            throw invalid(name + " must expose exactly health, info and prometheus");
        }
    }

    private static void requireCookiePolicy(Environment environment, String transport) {
        requireExact(environment, "server.reactive.session.cookie.name", "CREWSCOPE_SESSION");
        requireExact(environment, "server.reactive.session.cookie.path", "/");
        requireBoolean(environment, "server.reactive.session.cookie.http-only", true);
        requireExact(environment, "server.reactive.session.cookie.same-site", "lax");
        requireBoolean(
                environment,
                "server.reactive.session.cookie.secure",
                transport.equals("https"));
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
