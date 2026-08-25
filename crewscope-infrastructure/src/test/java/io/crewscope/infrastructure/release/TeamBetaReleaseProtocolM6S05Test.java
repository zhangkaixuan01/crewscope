package io.crewscope.infrastructure.release;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * M6-S05 test-only contract for the single-host Team Beta release environment.
 *
 * <p>The nested topology, metric, load, backup and release models are executable protocol probes.
 * M6-I08/I09/I10 and M6-Q03/Q04 will implement them using production configuration and scripts.
 */
class TeamBetaReleaseProtocolM6S05Test {

    private static final Instant CREATED_AT = Instant.parse("2026-08-25T10:00:00Z");
    private static final String SECRET_MARKER = "must-not-enter-release-evidence";

    @Test
    void validatesSingleHostTopologyWithSeparatedRolesAndOnePublicEntryPoint() {
        TeamBetaTopology topology = teamBetaTopology();

        TopologyValidation result = topology.validate();

        assertEquals(Set.of("web"), result.publicServices());
        assertEquals(Set.of("worker"), result.dockerSocketServices());
        assertEquals(Set.of(RuntimeRole.API, RuntimeRole.WORKER, RuntimeRole.WEB),
                result.applicationRoles());
        assertTrue(result.valid());
        assertThrows(ProtocolViolation.class, () -> topology.withService(
                topology.service("api").withRuntimeUser("root")).validate());
        assertThrows(ProtocolViolation.class, () -> topology.withService(
                topology.service("worker").withPublicPort("0.0.0.0:8081:8080")).validate());
        assertThrows(ProtocolViolation.class, () -> topology.withService(
                topology.service("web").withImage("crewscope/web:latest")).validate());
        assertThrows(ProtocolViolation.class, () -> topology.withoutService(
                "prometheus").validate());
        assertThrows(ProtocolViolation.class, () -> topology.withService(
                topology.service("api").withSecretReferences(Set.of("api-password=secret")))
                .validate());
    }

    @Test
    void fingerprintsEveryReleaseEnvironmentCoordinateWithoutSecretMaterial() {
        ReleaseEnvironment baseline = releaseEnvironment();
        ReleaseEnvironment identical = releaseEnvironment();
        ReleaseEnvironment changedConcurrency = baseline.withTaskConcurrency(3);

        assertEquals(baseline.fingerprint(), identical.fingerprint());
        assertNotEquals(baseline.fingerprint(), changedConcurrency.fingerprint());
        assertEquals(64, baseline.fingerprint().length());
        assertTrue(baseline.publicReport().contains("seed=20260825"));
        assertTrue(baseline.publicReport().contains("cpu=8"));
        assertTrue(baseline.publicReport().contains("memoryMiB=16384"));
        assertFalse(baseline.publicReport().contains(SECRET_MARKER));
        assertThrows(ProtocolViolation.class, () -> baseline.withDatasetVersion(SECRET_MARKER));
    }

    @Test
    void boundsPrometheusSeriesAndRejectsDynamicIdentityLabels() {
        MetricPolicy policy = MetricPolicy.teamBeta();
        List<MetricDefinition> definitions = List.of(
                new MetricDefinition(
                        "crewscope.projection.events", Set.of("projectionName", "outcome")),
                new MetricDefinition(
                        "crewscope.notification.attempts",
                        Set.of("providerKey", "operation", "outcome")),
                new MetricDefinition(
                        "crewscope.worker.claims", Set.of("workerRole", "status")),
                new MetricDefinition(
                        "crewscope.provider.errors", Set.of("providerKey", "errorCode")));

        MetricBudget budget = policy.validate(definitions);

        assertTrue(budget.totalSeriesUpperBound() <= 2_000);
        assertTrue(budget.maximumMetricSeries() <= 256);
        assertThrows(ProtocolViolation.class, () -> policy.validate(List.of(
                new MetricDefinition(
                        "crewscope.projection.events",
                        Set.of("projectionName", "organizationId")))));
        assertThrows(ProtocolViolation.class, () -> policy.validate(List.of(
                new MetricDefinition(
                        "crewscope.provider.errors", Set.of("providerKey", "exceptionMessage")))));
        assertThrows(ProtocolViolation.class, () -> policy.validate(List.of(
                new MetricDefinition(
                        "crewscope.unbounded", Set.of("errorCode", "operation", "status")))));
    }

    @Test
    void calculatesNearestRankP95AndEnforcesLoadAndFaultThresholdsPerRun() {
        LoadProtocol protocol = LoadProtocol.teamBeta();
        List<BenchmarkRun> passingRuns = IntStream.range(0, 3)
                .mapToObj(ignored -> passingRun())
                .toList();

        LoadGateResult passing = protocol.evaluate(
                passingRuns,
                new FaultEvidence(100, 99, 0, 0, 0, 0));

        assertEquals(Duration.ofMillis(1_900), passing.worstClaimP95());
        assertEquals(Duration.ofMillis(1_900), passing.worstProjectionP95());
        assertEquals(0.99, passing.recoveryRate());
        assertTrue(passing.passed());

        List<BenchmarkRun> slowRuns = new ArrayList<>(passingRuns);
        slowRuns.set(2, runWithP95(Duration.ofMillis(2_000)));
        assertFalse(protocol.evaluate(
                slowRuns, new FaultEvidence(100, 99, 0, 0, 0, 0)).passed());
        assertFalse(protocol.evaluate(
                passingRuns, new FaultEvidence(100, 98, 0, 0, 0, 0)).passed());
        assertFalse(protocol.evaluate(
                passingRuns, new FaultEvidence(100, 100, 0, 1, 0, 0)).passed());
    }

    @Test
    void verifiesImmutableBackupManifestAndFailsClosedBeforeOrderedRestore() {
        BackupPolicy policy = BackupPolicy.teamBeta();
        Map<BackupComponent, byte[]> components = backupComponents();
        BackupBundle bundle = policy.create(
                new QuiescenceProof(true, 0, 0, 0),
                CREATED_AT,
                "0.1.0-m6",
                28,
                Set.of("credential-key-2026-08"),
                components);

        RestorePlan restore = policy.verifyAndPlanRestore(
                bundle, components, true, 28, CREATED_AT.plus(Duration.ofHours(23)),
                Duration.ofHours(3));

        assertEquals(Duration.ofHours(24), policy.maximumRpo());
        assertEquals(Duration.ofHours(4), policy.maximumRto());
        assertEquals(List.of(
                RestoreStep.VERIFY_MANIFEST,
                RestoreStep.RESTORE_POSTGRES,
                RestoreStep.RESTORE_ARTIFACTS,
                RestoreStep.RESTORE_REDIS_OR_REBUILD,
                RestoreStep.VERIFY_REFERENCES,
                RestoreStep.REBUILD_PROJECTIONS,
                RestoreStep.START_MAINTENANCE,
                RestoreStep.SMOKE_TEST,
                RestoreStep.ENABLE_TRAFFIC), restore.steps());
        assertTrue(bundle.manifest().encrypted());
        assertFalse(bundle.manifest().toString().contains(SECRET_MARKER));
        assertThrows(ProtocolViolation.class, () -> policy.create(
                new QuiescenceProof(false, 1, 0, 0), CREATED_AT, "0.1.0-m6", 28,
                Set.of("credential-key-2026-08"), components));

        Map<BackupComponent, byte[]> tampered = backupComponents();
        tampered.put(BackupComponent.ARTIFACTS, "tampered".getBytes(StandardCharsets.UTF_8));
        assertThrows(ProtocolViolation.class,
                () -> policy.verifyAndPlanRestore(
                        bundle, tampered, true, 28, CREATED_AT.plus(Duration.ofHours(23)),
                        Duration.ofHours(3)));
        assertThrows(ProtocolViolation.class,
                () -> policy.verifyAndPlanRestore(
                        bundle, components, false, 28, CREATED_AT.plus(Duration.ofHours(23)),
                        Duration.ofHours(3)));
        assertThrows(ProtocolViolation.class,
                () -> policy.verifyAndPlanRestore(
                        bundle, components, true, 28, CREATED_AT.plus(Duration.ofHours(25)),
                        Duration.ofHours(3)));
        assertThrows(ProtocolViolation.class,
                () -> policy.verifyAndPlanRestore(
                        bundle, components, true, 28, CREATED_AT.minusSeconds(1),
                        Duration.ofHours(3)));
    }

    @Test
    void separatesCredentialFreeCiFromProtectedRealProviderReleaseEvidence() {
        ReleaseGatePlan gate = ReleaseGatePlan.teamBeta();

        gate.validate();
        assertFalse(gate.lane(GateLane.PULL_REQUEST).usesCredentials());
        assertFalse(gate.lane(GateLane.NIGHTLY).usesCredentials());
        assertTrue(gate.lane(GateLane.RELEASE_CANDIDATE).usesCredentials());
        assertTrue(gate.lane(GateLane.RELEASE_CANDIDATE).manualProtectedEnvironment());
        assertEquals("dedicated-lark-test-recipient",
                gate.lane(GateLane.RELEASE_CANDIDATE).realProviderTarget());

        Map<String, GateStatus> success = gate.requiredStepNames().stream()
                .collect(java.util.stream.Collectors.toMap(
                        name -> name, ignored -> GateStatus.SUCCESS));
        assertTrue(gate.decide(success).released());
        Map<String, GateStatus> skipped = new HashMap<>(success);
        skipped.put("backup-restore", GateStatus.SKIPPED);
        assertFalse(gate.decide(skipped).released());
        Map<String, GateStatus> missing = new HashMap<>(success);
        missing.remove("fault-matrix");
        assertFalse(gate.decide(missing).released());
        assertThrows(ProtocolViolation.class, () -> gate.withStep(new GateStep(
                "backend", GateLane.PULL_REQUEST, Set.of("real-lark-smoke"),
                true, false, true)).validate());
        assertThrows(ProtocolViolation.class, () -> gate.withLane(new GateLanePolicy(
                GateLane.PULL_REQUEST, true, false, "fixture-only")).validate());
    }

    private static TeamBetaTopology teamBetaTopology() {
        String appDigest = "@sha256:" + "a".repeat(64);
        String dataDigest = "@sha256:" + "b".repeat(64);
        return new TeamBetaTopology(List.of(
                service(
                        "postgres", "postgres:17-alpine" + dataDigest, RuntimeRole.DATA,
                        "postgres", false, Set.of(), Set.of("postgres-data"), Set.of(), false),
                service(
                        "redis", "redis:7.4-alpine" + dataDigest, RuntimeRole.DATA,
                        "redis", false, Set.of(), Set.of("redis-data"), Set.of(), false),
                service(
                        "otel-collector", "otel/opentelemetry-collector" + dataDigest,
                        RuntimeRole.OBSERVABILITY, "10001:10001", true, Set.of(), Set.of(),
                        Set.of(), false),
                service(
                        "prometheus", "prom/prometheus" + dataDigest,
                        RuntimeRole.OBSERVABILITY, "65534:65534", true, Set.of(),
                        Set.of("prometheus-data"), Set.of("otel-collector"), false),
                service(
                        "api", "ghcr.io/crewscope/server" + appDigest, RuntimeRole.API,
                        "10000:10000", true, Set.of(), Set.of("artifact-data"),
                        Set.of("postgres", "redis"), false),
                service(
                        "worker", "ghcr.io/crewscope/server" + appDigest, RuntimeRole.WORKER,
                        "10000:10000", true, Set.of(), Set.of(
                                "artifact-data", "repository-data", "worktree-data"),
                        Set.of("api", "postgres", "redis"), true),
                service(
                        "web", "ghcr.io/crewscope/web" + appDigest, RuntimeRole.WEB,
                        "101:101", true, Set.of("0.0.0.0:8443:8443"), Set.of(),
                        Set.of("api"), false)));
    }

    private static ServiceSpec service(
            String name,
            String image,
            RuntimeRole role,
            String user,
            boolean readOnlyRoot,
            Set<String> publicPorts,
            Set<String> volumes,
            Set<String> dependencies,
            boolean dockerSocket) {
        return new ServiceSpec(
                name, image, role, user, readOnlyRoot, publicPorts, volumes, dependencies,
                dockerSocket, true, Set.of("secret-ref:crewscope/runtime"));
    }

    private static ReleaseEnvironment releaseEnvironment() {
        return new ReleaseEnvironment(
                "temurin-17.0.16-linux-amd64",
                "apache-maven-3.9.11",
                "node-24.13.1",
                "pnpm-11.9.0",
                "docker-engine-29.6.2",
                "docker-compose-5.3.1",
                8,
                16_384,
                200,
                "m6-team-beta-v1",
                20_260_825L,
                10,
                2,
                500,
                3,
                120,
                600,
                "0123456789abcdef0123456789abcdef01234567",
                "sha256:" + "c".repeat(64),
                28);
    }

    private static BenchmarkRun passingRun() {
        List<Duration> measurement = percentileSamples(Duration.ofMillis(1_900));
        return new BenchmarkRun(
                List.of(Duration.ofSeconds(10)), measurement, measurement, 500, 0);
    }

    private static BenchmarkRun runWithP95(Duration p95) {
        List<Duration> measurement = percentileSamples(p95);
        return new BenchmarkRun(
                List.of(Duration.ofMillis(1)), measurement, measurement, 500, 0);
    }

    private static List<Duration> percentileSamples(Duration p95) {
        List<Duration> samples = new ArrayList<>();
        for (int index = 1; index <= 500; index++) {
            if (index < 475) {
                samples.add(Duration.ofMillis(1_000));
            } else if (index == 475) {
                samples.add(p95);
            } else {
                samples.add(Duration.ofMillis(3_000));
            }
        }
        return samples;
    }

    private static Map<BackupComponent, byte[]> backupComponents() {
        Map<BackupComponent, byte[]> components = new EnumMap<>(BackupComponent.class);
        components.put(
                BackupComponent.POSTGRES,
                "postgres-v28-consistent-dump".getBytes(StandardCharsets.UTF_8));
        components.put(
                BackupComponent.ARTIFACTS,
                "content-addressed-artifacts".getBytes(StandardCharsets.UTF_8));
        components.put(
                BackupComponent.REDIS_SNAPSHOT,
                "redis-agent-state-snapshot".getBytes(StandardCharsets.UTF_8));
        return components;
    }

    private enum RuntimeRole {
        DATA,
        API,
        WORKER,
        WEB,
        OBSERVABILITY
    }

    private record ServiceSpec(
            String name,
            String image,
            RuntimeRole role,
            String runtimeUser,
            boolean readOnlyRoot,
            Set<String> publicPorts,
            Set<String> volumes,
            Set<String> dependencies,
            boolean dockerSocket,
            boolean healthcheck,
            Set<String> secretReferences) {

        private ServiceSpec {
            requireToken(name, "service.name");
            requireText(image, "service.image");
            Objects.requireNonNull(role, "role");
            requireText(runtimeUser, "service.runtimeUser");
            publicPorts = Set.copyOf(publicPorts);
            volumes = Set.copyOf(volumes);
            dependencies = Set.copyOf(dependencies);
            secretReferences = Set.copyOf(secretReferences);
        }

        ServiceSpec withRuntimeUser(String user) {
            return new ServiceSpec(
                    name, image, role, user, readOnlyRoot, publicPorts, volumes, dependencies,
                    dockerSocket, healthcheck, secretReferences);
        }

        ServiceSpec withPublicPort(String port) {
            Set<String> changed = new LinkedHashSet<>(publicPorts);
            changed.add(port);
            return new ServiceSpec(
                    name, image, role, runtimeUser, readOnlyRoot, changed, volumes, dependencies,
                    dockerSocket, healthcheck, secretReferences);
        }

        ServiceSpec withImage(String changedImage) {
            return new ServiceSpec(
                    name, changedImage, role, runtimeUser, readOnlyRoot, publicPorts, volumes,
                    dependencies, dockerSocket, healthcheck, secretReferences);
        }

        ServiceSpec withSecretReferences(Set<String> changedReferences) {
            return new ServiceSpec(
                    name, image, role, runtimeUser, readOnlyRoot, publicPorts, volumes,
                    dependencies, dockerSocket, healthcheck, changedReferences);
        }
    }

    private record TopologyValidation(
            boolean valid,
            Set<String> publicServices,
            Set<String> dockerSocketServices,
            Set<RuntimeRole> applicationRoles) {}

    /** Validates the target M6-I09 topology without asserting the current placeholder Compose. */
    private static final class TeamBetaTopology {

        private static final Set<RuntimeRole> APPLICATION_ROLES =
                Set.of(RuntimeRole.API, RuntimeRole.WORKER, RuntimeRole.WEB);
        private final Map<String, ServiceSpec> services;

        TeamBetaTopology(List<ServiceSpec> values) {
            Map<String, ServiceSpec> indexed = new LinkedHashMap<>();
            for (ServiceSpec value : values) {
                if (indexed.putIfAbsent(value.name(), value) != null) {
                    throw new ProtocolViolation("service names must be unique");
                }
            }
            services = Map.copyOf(indexed);
        }

        ServiceSpec service(String name) {
            ServiceSpec value = services.get(name);
            if (value == null) {
                throw new ProtocolViolation("service does not exist");
            }
            return value;
        }

        TeamBetaTopology withService(ServiceSpec changed) {
            Map<String, ServiceSpec> values = new LinkedHashMap<>(services);
            values.put(changed.name(), changed);
            return new TeamBetaTopology(new ArrayList<>(values.values()));
        }

        TeamBetaTopology withoutService(String name) {
            Map<String, ServiceSpec> values = new LinkedHashMap<>(services);
            values.remove(name);
            return new TeamBetaTopology(new ArrayList<>(values.values()));
        }

        TopologyValidation validate() {
            Map<String, RuntimeRole> requiredServices = Map.of(
                    "postgres", RuntimeRole.DATA,
                    "redis", RuntimeRole.DATA,
                    "otel-collector", RuntimeRole.OBSERVABILITY,
                    "prometheus", RuntimeRole.OBSERVABILITY,
                    "api", RuntimeRole.API,
                    "worker", RuntimeRole.WORKER,
                    "web", RuntimeRole.WEB);
            if (!services.keySet().equals(requiredServices.keySet())) {
                throw new ProtocolViolation("Team Beta requires the exact seven-service topology");
            }
            Set<String> publicServices = new HashSet<>();
            Set<String> dockerServices = new HashSet<>();
            Set<RuntimeRole> applicationRoles = new HashSet<>();
            for (ServiceSpec service : services.values()) {
                if (service.role() != requiredServices.get(service.name())) {
                    throw new ProtocolViolation("service role does not match the frozen topology");
                }
                if (!service.image().matches(".+@sha256:[0-9a-f]{64}")) {
                    throw new ProtocolViolation("every image must use an immutable digest");
                }
                if (!service.healthcheck()) {
                    throw new ProtocolViolation("every service requires a healthcheck");
                }
                if (service.dependencies().stream().anyMatch(dependency -> !services.containsKey(dependency))) {
                    throw new ProtocolViolation("service dependency is missing");
                }
                if (!service.publicPorts().isEmpty()) {
                    publicServices.add(service.name());
                }
                if (service.dockerSocket()) {
                    dockerServices.add(service.name());
                }
                if (APPLICATION_ROLES.contains(service.role())) {
                    applicationRoles.add(service.role());
                    if (service.runtimeUser().equalsIgnoreCase("root")
                            || service.runtimeUser().equals("0")
                            || !service.readOnlyRoot()) {
                        throw new ProtocolViolation(
                                "API, Worker and Web must be non-root with read-only root filesystems");
                    }
                }
                if (service.secretReferences().isEmpty()
                        || service.secretReferences().stream()
                                .anyMatch(reference -> !reference.startsWith("secret-ref:")
                                        || reference.length() > 200)) {
                    throw new ProtocolViolation("Compose may only reference external secrets");
                }
            }
            if (!publicServices.equals(Set.of("web"))) {
                throw new ProtocolViolation("Web must be the only public service");
            }
            if (!dockerServices.equals(Set.of("worker"))
                    || service("worker").role() != RuntimeRole.WORKER) {
                throw new ProtocolViolation("only the trusted Worker may access the Docker socket");
            }
            if (!applicationRoles.equals(APPLICATION_ROLES)) {
                throw new ProtocolViolation("API, Worker and Web roles must be separated");
            }
            return new TopologyValidation(
                    true, Set.copyOf(publicServices), Set.copyOf(dockerServices),
                    Set.copyOf(applicationRoles));
        }
    }

    private record ReleaseEnvironment(
            String javaRuntime,
            String maven,
            String node,
            String pnpm,
            String docker,
            String compose,
            int cpu,
            int memoryMiB,
            int diskGiB,
            String datasetVersion,
            long seed,
            int webConcurrency,
            int taskConcurrency,
            int minimumSamplesPerMetricPerRun,
            int repetitions,
            int warmupSeconds,
            int measurementSeconds,
            String sourceRevision,
            String imageDigest,
            int schemaVersion) {

        private ReleaseEnvironment {
            List.of(javaRuntime, maven, node, pnpm, docker, compose, datasetVersion,
                            sourceRevision, imageDigest)
                    .forEach(value -> requireText(value, "environment coordinate"));
            if (!javaRuntime.startsWith("temurin-17")
                    || !node.startsWith("node-24.")
                    || !pnpm.equals("pnpm-11.9.0")
                    || !sourceRevision.matches("[0-9a-f]{40}")
                    || !imageDigest.matches("sha256:[0-9a-f]{64}")
                    || cpu < 8
                    || memoryMiB < 16_384
                    || diskGiB < 100
                    || webConcurrency < 1
                    || taskConcurrency < 1
                    || minimumSamplesPerMetricPerRun < 500
                    || repetitions != 3
                    || warmupSeconds < 60
                    || measurementSeconds < 300
                    || schemaVersion != 28) {
                throw new ProtocolViolation("release environment is outside the frozen M6 profile");
            }
            if (datasetVersion.toLowerCase().contains("secret")
                    || datasetVersion.equals(SECRET_MARKER)) {
                throw new ProtocolViolation("release environment identity cannot contain secrets");
            }
        }

        ReleaseEnvironment withTaskConcurrency(int value) {
            return copy(datasetVersion, value);
        }

        ReleaseEnvironment withDatasetVersion(String value) {
            return copy(value, taskConcurrency);
        }

        private ReleaseEnvironment copy(String changedDataset, int changedTaskConcurrency) {
            return new ReleaseEnvironment(
                    javaRuntime, maven, node, pnpm, docker, compose, cpu, memoryMiB, diskGiB,
                    changedDataset, seed, webConcurrency, changedTaskConcurrency,
                    minimumSamplesPerMetricPerRun, repetitions, warmupSeconds,
                    measurementSeconds, sourceRevision, imageDigest, schemaVersion);
        }

        String fingerprint() {
            return sha256(canonical());
        }

        String publicReport() {
            return "ReleaseEnvironment[fingerprint=" + fingerprint()
                    + ", java=" + javaRuntime
                    + ", node=" + node
                    + ", pnpm=" + pnpm
                    + ", cpu=" + cpu
                    + ", memoryMiB=" + memoryMiB
                    + ", diskGiB=" + diskGiB
                    + ", dataset=" + datasetVersion
                    + ", seed=" + seed
                    + ", webConcurrency=" + webConcurrency
                    + ", taskConcurrency=" + taskConcurrency
                    + ", samples=" + minimumSamplesPerMetricPerRun
                    + ", repetitions=" + repetitions
                    + "]";
        }

        private String canonical() {
            return Canonical.encode(
                    "team-beta-environment-v1", javaRuntime, maven, node, pnpm, docker, compose,
                    Integer.toString(cpu), Integer.toString(memoryMiB), Integer.toString(diskGiB),
                    datasetVersion, Long.toString(seed), Integer.toString(webConcurrency),
                    Integer.toString(taskConcurrency),
                    Integer.toString(minimumSamplesPerMetricPerRun), Integer.toString(repetitions),
                    Integer.toString(warmupSeconds), Integer.toString(measurementSeconds),
                    sourceRevision, imageDigest, Integer.toString(schemaVersion));
        }
    }

    private record MetricDefinition(String name, Set<String> labels) {

        private MetricDefinition {
            requireToken(name, "metric.name");
            labels = Set.copyOf(labels);
        }
    }

    private record MetricBudget(long totalSeriesUpperBound, long maximumMetricSeries) {}

    private static final class MetricPolicy {

        private final Map<String, Integer> labelCardinality;
        private final int maximumPerMetric;
        private final int maximumTotal;

        private MetricPolicy(
                Map<String, Integer> labelCardinality,
                int maximumPerMetric,
                int maximumTotal) {
            this.labelCardinality = Map.copyOf(labelCardinality);
            this.maximumPerMetric = maximumPerMetric;
            this.maximumTotal = maximumTotal;
        }

        static MetricPolicy teamBeta() {
            return new MetricPolicy(Map.of(
                    "outcome", 6,
                    "status", 8,
                    "type", 12,
                    "providerKey", 4,
                    "projectionName", 12,
                    "workerRole", 4,
                    "operation", 8,
                    "errorCode", 24,
                    "streamType", 3,
                    "result", 4), 256, 2_000);
        }

        MetricBudget validate(List<MetricDefinition> definitions) {
            Set<String> names = new HashSet<>();
            long total = 0;
            long maximum = 0;
            for (MetricDefinition definition : definitions) {
                if (!names.add(definition.name())) {
                    throw new ProtocolViolation("metric names must be unique");
                }
                long series = 1;
                for (String label : definition.labels()) {
                    Integer cardinality = labelCardinality.get(label);
                    if (cardinality == null) {
                        throw new ProtocolViolation("metric label is not in the bounded registry");
                    }
                    series = Math.multiplyExact(series, cardinality);
                }
                if (series > maximumPerMetric) {
                    throw new ProtocolViolation("metric exceeds its series budget");
                }
                total = Math.addExact(total, series);
                maximum = Math.max(maximum, series);
            }
            if (total > maximumTotal) {
                throw new ProtocolViolation("custom metrics exceed the Team Beta series budget");
            }
            return new MetricBudget(total, maximum);
        }
    }

    private record BenchmarkRun(
            List<Duration> discardedWarmup,
            List<Duration> claimLatencies,
            List<Duration> projectionLatencies,
            int requests,
            int errors) {

        private BenchmarkRun {
            discardedWarmup = List.copyOf(discardedWarmup);
            claimLatencies = List.copyOf(claimLatencies);
            projectionLatencies = List.copyOf(projectionLatencies);
            if (requests < 1 || errors < 0 || errors > requests) {
                throw new ProtocolViolation("benchmark request counters are invalid");
            }
        }
    }

    private record FaultEvidence(
            int samples,
            int automaticallyRecovered,
            int duplicateActionDispatches,
            int duplicateNotificationDispatches,
            int lostInboxDispositions,
            int staleFencingWrites) {

        double recoveryRate() {
            return samples == 0 ? 0 : automaticallyRecovered / (double) samples;
        }
    }

    private record LoadGateResult(
            boolean passed,
            Duration worstClaimP95,
            Duration worstProjectionP95,
            double recoveryRate) {}

    /** Freezes nearest-rank percentiles and evaluates every repetition independently. */
    private record LoadProtocol(
            int repetitions,
            int minimumSamples,
            Duration latencyTarget,
            double maximumErrorRate,
            int minimumFaultSamples,
            double minimumRecoveryRate) {

        static LoadProtocol teamBeta() {
            return new LoadProtocol(
                    3, 500, Duration.ofSeconds(2), 0.001, 100, 0.99);
        }

        LoadGateResult evaluate(List<BenchmarkRun> runs, FaultEvidence faults) {
            if (runs.size() != repetitions) {
                throw new ProtocolViolation("load gate requires exactly three measured repetitions");
            }
            Duration worstClaim = Duration.ZERO;
            Duration worstProjection = Duration.ZERO;
            boolean passed = true;
            for (BenchmarkRun run : runs) {
                if (run.claimLatencies().size() < minimumSamples
                        || run.projectionLatencies().size() < minimumSamples) {
                    throw new ProtocolViolation("each run requires the frozen minimum sample count");
                }
                Duration claim = nearestRank(run.claimLatencies(), 0.95);
                Duration projection = nearestRank(run.projectionLatencies(), 0.95);
                worstClaim = max(worstClaim, claim);
                worstProjection = max(worstProjection, projection);
                double errorRate = run.errors() / (double) run.requests();
                passed &= claim.compareTo(latencyTarget) < 0
                        && projection.compareTo(latencyTarget) < 0
                        && errorRate <= maximumErrorRate;
            }
            passed &= faults.samples() >= minimumFaultSamples
                    && faults.recoveryRate() >= minimumRecoveryRate
                    && faults.duplicateActionDispatches() == 0
                    && faults.duplicateNotificationDispatches() == 0
                    && faults.lostInboxDispositions() == 0
                    && faults.staleFencingWrites() == 0;
            return new LoadGateResult(
                    passed, worstClaim, worstProjection, faults.recoveryRate());
        }

        private static Duration nearestRank(List<Duration> values, double percentile) {
            List<Duration> sorted = values.stream().sorted().toList();
            int rank = (int) Math.ceil(percentile * sorted.size());
            return sorted.get(Math.max(0, rank - 1));
        }

        private static Duration max(Duration left, Duration right) {
            return left.compareTo(right) >= 0 ? left : right;
        }
    }

    private enum BackupComponent {
        POSTGRES,
        ARTIFACTS,
        REDIS_SNAPSHOT
    }

    private enum RestoreStep {
        VERIFY_MANIFEST,
        RESTORE_POSTGRES,
        RESTORE_ARTIFACTS,
        RESTORE_REDIS_OR_REBUILD,
        VERIFY_REFERENCES,
        REBUILD_PROJECTIONS,
        START_MAINTENANCE,
        SMOKE_TEST,
        ENABLE_TRAFFIC
    }

    private record QuiescenceProof(
            boolean maintenanceMode,
            int activeExecutions,
            int activeActionDispatches,
            int activeNotificationDispatches) {

        boolean quiescent() {
            return maintenanceMode
                    && activeExecutions == 0
                    && activeActionDispatches == 0
                    && activeNotificationDispatches == 0;
        }
    }

    private record ComponentManifest(long bytes, String sha256) {}

    private record BackupManifest(
            String backupId,
            Instant createdAt,
            String applicationVersion,
            int schemaVersion,
            Set<String> requiredCredentialKeyIds,
            Map<BackupComponent, ComponentManifest> components,
            boolean encrypted,
            String digest) {

        @Override
        public String toString() {
            return "BackupManifest[backupId=" + backupId
                    + ", createdAt=" + createdAt
                    + ", applicationVersion=" + applicationVersion
                    + ", schemaVersion=" + schemaVersion
                    + ", requiredCredentialKeyIds=" + requiredCredentialKeyIds
                    + ", encrypted=" + encrypted
                    + ", digest=" + digest + "]";
        }
    }

    private record BackupBundle(BackupManifest manifest) {}

    private record RestorePlan(List<RestoreStep> steps, Duration measuredRto) {

        private RestorePlan {
            steps = List.copyOf(steps);
        }
    }

    /** Builds a hash-addressed encrypted manifest and verifies every byte before restore mutation. */
    private record BackupPolicy(Duration maximumRpo, Duration maximumRto) {

        static BackupPolicy teamBeta() {
            return new BackupPolicy(Duration.ofHours(24), Duration.ofHours(4));
        }

        BackupBundle create(
                QuiescenceProof quiescence,
                Instant createdAt,
                String applicationVersion,
                int schemaVersion,
                Set<String> requiredCredentialKeyIds,
                Map<BackupComponent, byte[]> components) {
            Objects.requireNonNull(createdAt, "createdAt");
            requireText(applicationVersion, "applicationVersion");
            requiredCredentialKeyIds = Set.copyOf(requiredCredentialKeyIds);
            components = Map.copyOf(components);
            if (!quiescence.quiescent()) {
                throw new ProtocolViolation("backup requires maintenance mode and zero active work");
            }
            if (schemaVersion < 1
                    || requiredCredentialKeyIds.isEmpty()
                    || requiredCredentialKeyIds.stream()
                            .anyMatch(keyId -> !keyId.matches("[A-Za-z0-9._-]{1,120}"))) {
                throw new ProtocolViolation("backup manifest coordinates are invalid");
            }
            if (!components.keySet().equals(Set.of(
                    BackupComponent.POSTGRES,
                    BackupComponent.ARTIFACTS,
                    BackupComponent.REDIS_SNAPSHOT))) {
                throw new ProtocolViolation("backup component set is incomplete");
            }
            if (components.values().stream().anyMatch(bytes -> bytes == null || bytes.length == 0)) {
                throw new ProtocolViolation("backup components must contain immutable bytes");
            }
            Map<BackupComponent, ComponentManifest> manifests = new EnumMap<>(BackupComponent.class);
            components.forEach((component, bytes) -> manifests.put(
                    component, new ComponentManifest(bytes.length, sha256(bytes))));
            String backupId = sha256(Canonical.encode(
                    createdAt.toString(), applicationVersion, Integer.toString(schemaVersion)))
                    .substring(0, 24);
            String digest = manifestDigest(
                    backupId, createdAt, applicationVersion, schemaVersion,
                    requiredCredentialKeyIds, manifests, true);
            return new BackupBundle(new BackupManifest(
                    backupId, createdAt, applicationVersion, schemaVersion,
                    Set.copyOf(requiredCredentialKeyIds), Map.copyOf(manifests), true, digest));
        }

        RestorePlan verifyAndPlanRestore(
                BackupBundle bundle,
                Map<BackupComponent, byte[]> componentBytes,
                boolean targetEmpty,
                int maximumSupportedSchema,
                Instant restoreStartedAt,
                Duration measuredRto) {
            BackupManifest manifest = bundle.manifest();
            Objects.requireNonNull(restoreStartedAt, "restoreStartedAt");
            Objects.requireNonNull(measuredRto, "measuredRto");
            if (!targetEmpty) {
                throw new ProtocolViolation("restore target must be empty");
            }
            Duration backupAge = Duration.between(manifest.createdAt(), restoreStartedAt);
            if (!manifest.encrypted()
                    || manifest.schemaVersion() > maximumSupportedSchema
                    || backupAge.isNegative()
                    || backupAge.compareTo(maximumRpo) > 0
                    || measuredRto.isNegative()
                    || measuredRto.compareTo(maximumRto) > 0
                    || !componentBytes.keySet().equals(manifest.components().keySet())
                    || !manifest.digest().equals(manifestDigest(
                            manifest.backupId(), manifest.createdAt(),
                            manifest.applicationVersion(), manifest.schemaVersion(),
                            manifest.requiredCredentialKeyIds(), manifest.components(),
                            manifest.encrypted()))) {
                throw new ProtocolViolation("backup manifest is invalid or incompatible");
            }
            for (Map.Entry<BackupComponent, ComponentManifest> entry
                    : manifest.components().entrySet()) {
                byte[] bytes = componentBytes.get(entry.getKey());
                if (bytes == null
                        || bytes.length != entry.getValue().bytes()
                        || !sha256(bytes).equals(entry.getValue().sha256())) {
                    throw new ProtocolViolation("backup component integrity check failed");
                }
            }
            return new RestorePlan(List.of(
                    RestoreStep.VERIFY_MANIFEST,
                    RestoreStep.RESTORE_POSTGRES,
                    RestoreStep.RESTORE_ARTIFACTS,
                    RestoreStep.RESTORE_REDIS_OR_REBUILD,
                    RestoreStep.VERIFY_REFERENCES,
                    RestoreStep.REBUILD_PROJECTIONS,
                    RestoreStep.START_MAINTENANCE,
                    RestoreStep.SMOKE_TEST,
                    RestoreStep.ENABLE_TRAFFIC), measuredRto);
        }

        private static String manifestDigest(
                String backupId,
                Instant createdAt,
                String applicationVersion,
                int schemaVersion,
                Set<String> keyIds,
                Map<BackupComponent, ComponentManifest> components,
                boolean encrypted) {
            List<String> values = new ArrayList<>(List.of(
                    "team-beta-backup-v1", backupId, createdAt.toString(), applicationVersion,
                    Integer.toString(schemaVersion), Boolean.toString(encrypted)));
            keyIds.stream().sorted().forEach(values::add);
            components.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> {
                        values.add(entry.getKey().name());
                        values.add(Long.toString(entry.getValue().bytes()));
                        values.add(entry.getValue().sha256());
                    });
            return sha256(Canonical.encode(values.toArray(String[]::new)));
        }
    }

    private enum GateLane {
        PULL_REQUEST,
        NIGHTLY,
        RELEASE_CANDIDATE
    }

    private enum GateStatus {
        SUCCESS,
        FAILED,
        SKIPPED
    }

    private record GateStep(
            String name,
            GateLane lane,
            Set<String> dependencies,
            boolean required,
            boolean usesCredentials,
            boolean archivesEvidence) {

        private GateStep {
            requireToken(name, "gateStep.name");
            Objects.requireNonNull(lane, "lane");
            dependencies = Set.copyOf(dependencies);
        }
    }

    private record GateLanePolicy(
            GateLane lane,
            boolean usesCredentials,
            boolean manualProtectedEnvironment,
            String realProviderTarget) {}

    private record ReleaseDecision(boolean released, List<String> blockers) {

        private ReleaseDecision {
            blockers = List.copyOf(blockers);
        }
    }

    /** Release graph keeps real credentials out of automatic CI and rejects required skips. */
    private static final class ReleaseGatePlan {

        private final Map<String, GateStep> steps;
        private final Map<GateLane, GateLanePolicy> lanes;

        private ReleaseGatePlan(
                List<GateStep> steps, List<GateLanePolicy> lanes) {
            Map<String, GateStep> indexed = new LinkedHashMap<>();
            for (GateStep step : steps) {
                if (indexed.putIfAbsent(step.name(), step) != null) {
                    throw new ProtocolViolation("release step names must be unique");
                }
            }
            this.steps = Map.copyOf(indexed);
            Map<GateLane, GateLanePolicy> laneIndex = new EnumMap<>(GateLane.class);
            for (GateLanePolicy lane : lanes) {
                if (laneIndex.putIfAbsent(lane.lane(), lane) != null) {
                    throw new ProtocolViolation("release lane policies must be unique");
                }
            }
            this.lanes = Map.copyOf(laneIndex);
        }

        static ReleaseGatePlan teamBeta() {
            List<GateStep> steps = List.of(
                    step("backend", GateLane.PULL_REQUEST, Set.of(), false),
                    step("frontend", GateLane.PULL_REQUEST, Set.of(), false),
                    step("quality-security", GateLane.PULL_REQUEST, Set.of(), false),
                    step("dependency-image-scan", GateLane.PULL_REQUEST, Set.of(), false),
                    step("compose-clean-start", GateLane.NIGHTLY,
                            Set.of("backend", "frontend"), false),
                    step("fault-matrix", GateLane.NIGHTLY,
                            Set.of("compose-clean-start"), false),
                    step("load-profile", GateLane.NIGHTLY,
                            Set.of("compose-clean-start"), false),
                    step("backup-restore", GateLane.NIGHTLY,
                            Set.of("compose-clean-start"), false),
                    step("mvp-e2e-fixture", GateLane.NIGHTLY,
                            Set.of("fault-matrix", "backup-restore"), false),
                    step("real-lark-smoke", GateLane.RELEASE_CANDIDATE,
                            Set.of("mvp-e2e-fixture", "load-profile"), true),
                    step("release-manifest", GateLane.RELEASE_CANDIDATE,
                            Set.of("real-lark-smoke", "dependency-image-scan"), false));
            return new ReleaseGatePlan(steps, List.of(
                    new GateLanePolicy(GateLane.PULL_REQUEST, false, false, "fixture-only"),
                    new GateLanePolicy(GateLane.NIGHTLY, false, false, "fixture-only"),
                    new GateLanePolicy(
                            GateLane.RELEASE_CANDIDATE, true, true,
                            "dedicated-lark-test-recipient")));
        }

        private static GateStep step(
                String name, GateLane lane, Set<String> dependencies, boolean credentials) {
            return new GateStep(name, lane, dependencies, true, credentials, true);
        }

        GateLanePolicy lane(GateLane lane) {
            return lanes.get(lane);
        }

        ReleaseGatePlan withStep(GateStep changed) {
            Map<String, GateStep> values = new LinkedHashMap<>(steps);
            values.put(changed.name(), changed);
            return new ReleaseGatePlan(
                    new ArrayList<>(values.values()), new ArrayList<>(lanes.values()));
        }

        ReleaseGatePlan withLane(GateLanePolicy changed) {
            Map<GateLane, GateLanePolicy> values = new EnumMap<>(GateLane.class);
            values.putAll(lanes);
            values.put(changed.lane(), changed);
            return new ReleaseGatePlan(
                    new ArrayList<>(steps.values()), new ArrayList<>(values.values()));
        }

        Set<String> requiredStepNames() {
            return steps.values().stream()
                    .filter(GateStep::required)
                    .map(GateStep::name)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }

        void validate() {
            if (!lanes.keySet().equals(Set.of(GateLane.values()))) {
                throw new ProtocolViolation("every release lane requires a policy");
            }
            for (GateLanePolicy lane : lanes.values()) {
                requireText(lane.realProviderTarget(), "realProviderTarget");
                if (lane.lane() != GateLane.RELEASE_CANDIDATE
                        && (lane.usesCredentials() || lane.manualProtectedEnvironment())) {
                    throw new ProtocolViolation(
                            "automatic release lanes cannot declare credential capability");
                }
                if (lane.lane() == GateLane.RELEASE_CANDIDATE
                        && (!lane.usesCredentials()
                                || !lane.manualProtectedEnvironment()
                                || !lane.realProviderTarget().equals(
                                        "dedicated-lark-test-recipient"))) {
                    throw new ProtocolViolation(
                            "release candidate lane requires the dedicated protected target");
                }
            }
            for (GateStep step : steps.values()) {
                if (!step.archivesEvidence() || !step.required()) {
                    throw new ProtocolViolation("every M6 release step is required and archived");
                }
                if (step.dependencies().stream().anyMatch(dependency -> !steps.containsKey(dependency))) {
                    throw new ProtocolViolation("release step dependency is missing");
                }
                if (step.dependencies().stream().anyMatch(dependency ->
                        steps.get(dependency).lane().ordinal() > step.lane().ordinal())) {
                    throw new ProtocolViolation(
                            "release step cannot depend on a later release lane");
                }
                if (step.usesCredentials() && step.lane() != GateLane.RELEASE_CANDIDATE) {
                    throw new ProtocolViolation("automatic CI lanes cannot use real credentials");
                }
                GateLanePolicy lane = lanes.get(step.lane());
                if (step.usesCredentials()
                        && (!lane.usesCredentials() || !lane.manualProtectedEnvironment())) {
                    throw new ProtocolViolation("credentialed step requires protected manual lane");
                }
            }
            detectCycles();
        }

        ReleaseDecision decide(Map<String, GateStatus> outcomes) {
            validate();
            List<String> blockers = new ArrayList<>();
            for (String required : requiredStepNames()) {
                if (outcomes.get(required) != GateStatus.SUCCESS) {
                    blockers.add(required);
                }
            }
            blockers.sort(Comparator.naturalOrder());
            return new ReleaseDecision(blockers.isEmpty(), blockers);
        }

        private void detectCycles() {
            Set<String> visiting = new HashSet<>();
            Set<String> visited = new HashSet<>();
            for (String step : steps.keySet()) {
                visit(step, visiting, visited);
            }
        }

        private void visit(String name, Set<String> visiting, Set<String> visited) {
            if (visited.contains(name)) {
                return;
            }
            if (!visiting.add(name)) {
                throw new ProtocolViolation("release gate contains a dependency cycle");
            }
            for (String dependency : steps.get(name).dependencies()) {
                visit(dependency, visiting, visited);
            }
            visiting.remove(name);
            visited.add(name);
        }
    }

    private static final class Canonical {

        static String encode(String... values) {
            StringBuilder canonical = new StringBuilder();
            for (String value : values) {
                String required = Objects.requireNonNull(value, "canonical value");
                canonical.append('|').append(required.length()).append(':').append(required);
            }
            return canonical.toString();
        }
    }

    private static String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ProtocolViolation(field + " is required");
        }
        return value;
    }

    private static String requireToken(String value, String field) {
        String required = requireText(value, field);
        if (!required.matches("[a-z][a-z0-9._-]{0,127}")) {
            throw new ProtocolViolation(field + " has an invalid stable token shape");
        }
        return required;
    }

    private static final class ProtocolViolation extends RuntimeException {

        ProtocolViolation(String message) {
            super(message);
        }
    }
}
