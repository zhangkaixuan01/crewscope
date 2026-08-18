package io.crewscope.application.coding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.coding.BuildCommand;
import io.crewscope.domain.coding.BuildProfile;
import io.crewscope.domain.coding.BuildProfileReference;
import io.crewscope.domain.coding.BuildTool;
import io.crewscope.domain.coding.CommandCatalog;
import io.crewscope.domain.coding.CommandKind;
import io.crewscope.domain.coding.SandboxImageReference;
import io.crewscope.domain.coding.WorkspacePolicyId;
import io.crewscope.domain.coding.WorkspacePolicyOverlay;
import io.crewscope.domain.coding.WorkspacePolicyOverlayId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskFactHash;
import io.crewscope.domain.workitem.WorkProjectId;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Executable exact-version, scoped-query and optimistic-overlay Port contract. */
class WorkspacePolicyPortContractTest {

    @Test
    void buildProfileCatalogNeverFallsForwardFromAnExactReference() {
        BuildProfile versionOne = profile(1);
        BuildProfile versionTwo = profile(2);
        BuildProfileCatalog catalog = reference -> List.of(versionOne, versionTwo).stream()
                .filter(candidate -> candidate.reference().equals(reference))
                .findFirst();

        assertEquals(versionOne.profileHash(),
                catalog.findExact(versionOne.reference()).orElseThrow().profileHash());
        assertEquals(Optional.empty(), catalog.findExact(new BuildProfileReference(
                versionOne.key(), versionOne.version(), TaskFactHash.sha256("unknown"))));
    }

    @Test
    void repositoryPortsRequireCompleteScopeAndOverlayCompareAndSetFacts() throws Exception {
        Method policyByExecution = WorkspacePolicyRepository.class.getMethod(
                "findByTaskExecution",
                OrganizationId.class,
                TeamId.class,
                WorkProjectId.class,
                TaskExecutionId.class);
        Method currentOverlay = WorkspacePolicyOverlayRepository.class.getMethod(
                "findCurrentByPolicy",
                OrganizationId.class,
                TeamId.class,
                WorkProjectId.class,
                WorkspacePolicyId.class);
        Method append = WorkspacePolicyOverlayRepository.class.getMethod(
                "appendSuccessor", WorkspacePolicyOverlay.class, TaskFactHash.class);
        Method exactOverlay = WorkspacePolicyOverlayRepository.class.getMethod(
                "findByIdAndVersion",
                OrganizationId.class,
                TeamId.class,
                WorkProjectId.class,
                WorkspacePolicyOverlayId.class,
                long.class);

        assertTrue(Optional.class.isAssignableFrom(policyByExecution.getReturnType()));
        assertTrue(Optional.class.isAssignableFrom(currentOverlay.getReturnType()));
        assertEquals(WorkspacePolicyOverlay.class, append.getReturnType());
        assertTrue(Optional.class.isAssignableFrom(exactOverlay.getReturnType()));
    }

    private static BuildProfile profile(long version) {
        return BuildProfile.define(
                "maven-java-17",
                version,
                BuildTool.MAVEN_WRAPPER,
                17,
                new SandboxImageReference(
                        "eclipse-temurin:17-jdk@sha256:" + "a".repeat(64)),
                CommandCatalog.of(
                        CommandKind.TEST,
                        new BuildCommand(
                                "command.mavenTest",
                                List.of("./mvnw", "test"),
                                ".",
                                60,
                                900)));
    }
}
