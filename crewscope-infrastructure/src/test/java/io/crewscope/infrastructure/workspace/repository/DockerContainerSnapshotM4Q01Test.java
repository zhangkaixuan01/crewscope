package io.crewscope.infrastructure.workspace.repository;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/** Fixed M4-Q01 attacks against the immutable Docker mount and environment surfaces. */
class DockerContainerSnapshotM4Q01Test {

    private static final Path WORKTREE = Path.of("/srv/crewscope/worktrees/current");
    private static final String DESTINATION = "/workspace/repository";
    private static final Path DEPENDENCY_CACHE = Path.of("/srv/crewscope/cache/m4-q03");
    private static final String DEPENDENCY_CACHE_DESTINATION = "/maven-cache";
    private static final Set<String> ALLOWED_ENVIRONMENT = Set.of(
            "PATH", "JAVA_HOME", "HOME", "MAVEN_CONFIG", "TMPDIR", "CI", "LANG");
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void acceptsOnlyTheSingleReviewedWorktreeMount() throws Exception {
        DockerContainerSnapshot snapshot = snapshot(
                List.of(mount("bind", WORKTREE.toString(), DESTINATION, true)),
                safeEnvironment());

        assertTrue(snapshot.hasOnlyReadWriteBindMount(WORKTREE, DESTINATION));
        assertTrue(snapshot.hasExactlyEnvironmentNames(ALLOWED_ENVIRONMENT));
    }

    @Test
    void acceptsTheReviewedReadOnlyDependencyCacheAlongsideTheWorktree() throws Exception {
        DockerContainerSnapshot snapshot = snapshot(
                List.of(
                        mount("bind", WORKTREE.toString(), DESTINATION, true),
                        mount(
                                "bind",
                                DEPENDENCY_CACHE.toString(),
                                DEPENDENCY_CACHE_DESTINATION,
                                false)),
                safeEnvironment());

        assertTrue(snapshot.hasOnlyExpectedBindMounts(
                WORKTREE,
                DESTINATION,
                Optional.of(DEPENDENCY_CACHE),
                DEPENDENCY_CACHE_DESTINATION));
    }

    @Test
    void rejectsAWritableOrSubstitutedDependencyCache() throws Exception {
        assertFalse(snapshot(
                        List.of(
                                mount("bind", WORKTREE.toString(), DESTINATION, true),
                                mount(
                                        "bind",
                                        DEPENDENCY_CACHE.toString(),
                                        DEPENDENCY_CACHE_DESTINATION,
                                        true)),
                        safeEnvironment())
                .hasOnlyExpectedBindMounts(
                        WORKTREE,
                        DESTINATION,
                        Optional.of(DEPENDENCY_CACHE),
                        DEPENDENCY_CACHE_DESTINATION));
        assertFalse(snapshot(
                        List.of(
                                mount("bind", WORKTREE.toString(), DESTINATION, true),
                                mount(
                                        "bind",
                                        "/srv/crewscope/cache/other",
                                        DEPENDENCY_CACHE_DESTINATION,
                                        false)),
                        safeEnvironment())
                .hasOnlyExpectedBindMounts(
                        WORKTREE,
                        DESTINATION,
                        Optional.of(DEPENDENCY_CACHE),
                        DEPENDENCY_CACHE_DESTINATION));
    }

    @TestFactory
    Stream<DynamicTest> blocksEveryAdditionalOrSubstitutedMount() {
        List<NamedMountAttack> attacks = List.of(
                new NamedMountAttack(
                        "MOUNT-DOCKER-SOCKET",
                        List.of(
                                mount("bind", WORKTREE.toString(), DESTINATION, true),
                                mount("bind", "/var/run/docker.sock", "/var/run/docker.sock", true))),
                new NamedMountAttack(
                        "MOUNT-HOST-CREDENTIALS",
                        List.of(
                                mount("bind", WORKTREE.toString(), DESTINATION, true),
                                mount("bind", "/home/worker/.ssh", "/home/worker/.ssh", false))),
                new NamedMountAttack(
                        "MOUNT-NAMED-VOLUME",
                        List.of(
                                mount("bind", WORKTREE.toString(), DESTINATION, true),
                                mount("volume", "/var/lib/docker/volumes/cache", "/cache", true))),
                new NamedMountAttack(
                        "MOUNT-READ-ONLY-WORKTREE",
                        List.of(mount("bind", WORKTREE.toString(), DESTINATION, false))),
                new NamedMountAttack(
                        "MOUNT-SOURCE-SUBSTITUTION",
                        List.of(mount("bind", "/srv/crewscope/other", DESTINATION, true))),
                new NamedMountAttack(
                        "MOUNT-DESTINATION-SUBSTITUTION",
                        List.of(mount("bind", WORKTREE.toString(), "/workspace/other", true))));
        return attacks.stream().map(attack -> dynamicTest(
                attack.name(),
                () -> assertFalse(snapshot(attack.mounts(), safeEnvironment())
                        .hasOnlyReadWriteBindMount(WORKTREE, DESTINATION))));
    }

    @TestFactory
    Stream<DynamicTest> blocksCredentialAndHostEnvironmentInjection() {
        List<String> attacks = List.of(
                "AWS_SECRET_ACCESS_KEY=q01-secret",
                "GITHUB_TOKEN=q01-token",
                "CREWSCOPE_DATABASE_PASSWORD=q01-password",
                "SSH_AUTH_SOCK=/Users/operator/.ssh/agent.sock",
                "DOCKER_HOST=unix:///var/run/docker.sock",
                "KUBECONFIG=/Users/operator/.kube/config",
                "HOST_WORKSPACE=/Users/operator/codes/crewscope-java");
        return attacks.stream().map(attack -> dynamicTest(
                "ENV-" + attack.substring(0, attack.indexOf('=')),
                () -> {
                    List<String> environment = new java.util.ArrayList<>(safeEnvironment());
                    environment.add(attack);
                    assertFalse(snapshot(
                                    List.of(mount(
                                            "bind", WORKTREE.toString(), DESTINATION, true)),
                                    environment)
                            .hasExactlyEnvironmentNames(ALLOWED_ENVIRONMENT));
                }));
    }

    @Test
    void rejectsDuplicateEnvironmentNames() throws Exception {
        List<String> environment = new java.util.ArrayList<>(safeEnvironment());
        environment.add("HOME=/host/home");

        assertFalse(snapshot(
                        List.of(mount("bind", WORKTREE.toString(), DESTINATION, true)), environment)
                .hasExactlyEnvironmentNames(ALLOWED_ENVIRONMENT));
    }

    private static DockerContainerSnapshot snapshot(
            List<JsonNode> mounts, List<String> environment) throws Exception {
        JsonNode inspect = JSON.createObjectNode()
                .set("Mounts", JSON.valueToTree(mounts));
        ((com.fasterxml.jackson.databind.node.ObjectNode) inspect)
                .set("Config", JSON.createObjectNode().set("Env", JSON.valueToTree(environment)));
        return new DockerContainerSnapshot(inspect);
    }

    private static JsonNode mount(String type, String source, String destination, boolean readWrite) {
        return JSON.createObjectNode()
                .put("Type", type)
                .put("Source", source)
                .put("Destination", destination)
                .put("RW", readWrite);
    }

    private static List<String> safeEnvironment() {
        return List.of(
                "PATH=/usr/bin:/bin",
                "JAVA_HOME=/opt/java/openjdk",
                "HOME=/tmp/crewscope-home",
                "MAVEN_CONFIG=/tmp/crewscope-home/.m2",
                "TMPDIR=/tmp",
                "CI=true",
                "LANG=C.UTF-8");
    }

    private record NamedMountAttack(String name, List<JsonNode> mounts) {}
}
