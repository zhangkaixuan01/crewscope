package io.crewscope.domain.coding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.task.TaskFactHash;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BuildProfileTest {

    private static final SandboxImageReference IMAGE = new SandboxImageReference(
            "eclipse-temurin:17-jdk@sha256:" + "a".repeat(64));

    @Test
    void canonicalizesAllowedPathsAndProvesSubsetSemantics() {
        AllowedPathSet paths = new AllowedPathSet(List.of("src/test", "src", "docs", "src"));

        assertEquals(List.of("docs", "src"), paths.values());
        assertTrue(paths.allows("src/main/App.java"));
        assertTrue(paths.containsAll(AllowedPathSet.of("src/main", "docs/api")));
        assertFalse(paths.containsAll(AllowedPathSet.of("scripts")));
        assertThrows(DomainValidationException.class, () -> AllowedPathSet.of("../secret"));
    }

    @Test
    void acceptsTypedArgvOnlyForTheSelectedBuildTool() {
        BuildCommand maven = command("command.mavenTest", List.of("./mvnw", "test"));

        assertEquals("./mvnw", maven.argv().get(0));
        assertThrows(
                DomainValidationException.class,
                () -> profile(BuildTool.MAVEN, CommandCatalog.of(CommandKind.TEST, maven)));
        assertThrows(
                DomainValidationException.class,
                () -> command("command.bad", List.of("sh", "-c", "./mvnw test"))
                        .validateFor(BuildTool.MAVEN_WRAPPER));
        assertThrows(
                DomainValidationException.class,
                () -> command("command.bad", List.of("./mvnw", "test\nwhoami")));
    }

    @Test
    void validatesCanonicalWorkingDirectoriesAndProjectScripts() {
        assertThrows(
                DomainValidationException.class,
                () -> new BuildCommand("command.test", List.of("./mvnw", "test"), "../repo", 30, 60));
        assertThrows(
                DomainValidationException.class,
                () -> profile(
                        BuildTool.PROJECT_SCRIPT,
                        CommandCatalog.of(
                                CommandKind.TEST,
                                command("command.test", List.of("./scripts/../escape", "test")))));

        BuildProfile scripts = profile(
                BuildTool.PROJECT_SCRIPT,
                CommandCatalog.of(
                        CommandKind.TEST,
                        command("command.test", List.of("./scripts/check", "test"))));
        assertEquals(BuildTool.PROJECT_SCRIPT, scripts.buildTool());
    }

    @Test
    void boundsModuleAndExactTestSelectorsWithoutAcceptingExtraArguments() {
        CommandSelectorPolicy selectors = new CommandSelectorPolicy(
                List.of("crewscope-domain", "crewscope-application"), 2, 3, 128);
        BuildCommand command = new BuildCommand(
                "command.mavenTest",
                List.of("./mvnw", "test"),
                ".",
                60,
                900,
                selectors);

        assertTrue(command.selectorPolicy().allowsModules(List.of("crewscope-domain")));
        assertFalse(command.selectorPolicy().allowsModules(List.of("docs")));
        assertTrue(command.selectorPolicy().allowsTests(List.of("WorkspacePolicyTest#closesFacts")));
        assertFalse(command.selectorPolicy().allowsTests(List.of("*Test")));
        assertFalse(command.selectorPolicy().allowsTests(List.of(
                "OneTest", "TwoTest", "ThreeTest", "FourTest")));
    }

    @Test
    void rejectsDuplicateToolKeysAndChangedCommandsDuringCatalogNarrowing() {
        BuildCommand test = command("command.mavenTest", List.of("./mvnw", "test"));
        BuildCommand verify = command("command.mavenVerify", List.of("./mvnw", "verify"));
        CommandCatalog full = new CommandCatalog(Map.of(
                CommandKind.TEST, test,
                CommandKind.VERIFY, verify));

        assertTrue(full.containsUnchanged(CommandCatalog.of(CommandKind.TEST, test)));
        assertFalse(full.containsUnchanged(CommandCatalog.of(
                CommandKind.TEST,
                command("command.mavenTest", List.of("./mvnw", "test", "-DskipITs")))));
        assertTrue(full.containsUnchanged(new CommandCatalog(Map.of())));
        assertThrows(
                DomainValidationException.class,
                () -> profile(BuildTool.MAVEN_WRAPPER, new CommandCatalog(Map.of())));
        assertThrows(
                DomainValidationException.class,
                () -> new CommandCatalog(Map.of(
                        CommandKind.TEST, test,
                        CommandKind.VERIFY,
                                command("command.mavenTest", List.of("./mvnw", "verify")))));
    }

    @Test
    void producesAnOrderIndependentHashAndRejectsTamperedReconstitution() {
        BuildCommand test = command("command.mavenTest", List.of("./mvnw", "test"));
        BuildCommand verify = command("command.mavenVerify", List.of("./mvnw", "verify"));
        Map<CommandKind, BuildCommand> firstOrder = new LinkedHashMap<>();
        firstOrder.put(CommandKind.TEST, test);
        firstOrder.put(CommandKind.VERIFY, verify);
        Map<CommandKind, BuildCommand> secondOrder = new LinkedHashMap<>();
        secondOrder.put(CommandKind.VERIFY, verify);
        secondOrder.put(CommandKind.TEST, test);

        BuildProfile first = profile(BuildTool.MAVEN_WRAPPER, new CommandCatalog(firstOrder));
        BuildProfile second = profile(BuildTool.MAVEN_WRAPPER, new CommandCatalog(secondOrder));

        assertEquals(first.profileHash(), second.profileHash());
        assertEquals(first.reference(), second.reference());
        assertThrows(
                DomainValidationException.class,
                () -> BuildProfile.reconstitute(
                        first.key(),
                        first.version(),
                        first.buildTool(),
                        first.javaRelease(),
                        first.sandboxImage(),
                        first.commandCatalog(),
                        TaskFactHash.sha256("tampered")));
    }

    @Test
    void requiresDigestPinnedImagesAndBoundedResourceBudgets() {
        assertThrows(
                DomainValidationException.class,
                () -> new SandboxImageReference("eclipse-temurin:17-jdk"));
        assertThrows(
                DomainValidationException.class,
                () -> new SandboxResourceBudget(
                        SandboxNetworkMode.NONE, 0, 2_048, 256, 900, 1_048_576, true));
        assertThrows(
                DomainValidationException.class,
                () -> new WorkspaceOperationBudget(12, 20, 2_000, 80, 1_000, 524_288, 3));
    }

    @Test
    void comparesEveryNetworkResourceFileDiffAndOutputCeiling() {
        SandboxResourceBudget baseSandbox = sandbox();
        WorkspaceOperationBudget baseOperations = operations();

        assertTrue(new SandboxResourceBudget(
                        SandboxNetworkMode.NONE, 1, 1_024, 128, 600, 512_000, true)
                .isNoBroaderThan(baseSandbox));
        assertFalse(new SandboxResourceBudget(
                        SandboxNetworkMode.LOOPBACK_ONLY, 1, 1_024, 128, 600, 512_000, true)
                .isNoBroaderThan(baseSandbox));
        assertTrue(new WorkspaceOperationBudget(10, 10, 131_072, 40, 524_288, 262_144, 2)
                .isNoBroaderThan(baseOperations));
        assertFalse(new WorkspaceOperationBudget(13, 10, 131_072, 40, 524_288, 262_144, 2)
                .isNoBroaderThan(baseOperations));
    }

    private static BuildProfile profile(BuildTool tool, CommandCatalog catalog) {
        return BuildProfile.define("maven-java-17", 1, tool, 17, IMAGE, catalog);
    }

    private static BuildCommand command(String key, List<String> argv) {
        return new BuildCommand(key, argv, ".", 60, 900);
    }

    static SandboxResourceBudget sandbox() {
        return new SandboxResourceBudget(
                SandboxNetworkMode.NONE, 2, 2_048, 256, 900, 1_048_576, true);
    }

    static WorkspaceOperationBudget operations() {
        return new WorkspaceOperationBudget(12, 20, 262_144, 80, 1_048_576, 524_288, 3);
    }
}
