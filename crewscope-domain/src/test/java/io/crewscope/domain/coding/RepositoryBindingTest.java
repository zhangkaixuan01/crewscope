package io.crewscope.domain.coding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalStatus;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.shared.error.DomainErrorCode;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.InvalidStateTransitionException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamInitialization;
import io.crewscope.domain.workitem.WorkProject;
import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.domain.workitem.WorkProjectKey;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RepositoryBindingTest {

    private static final UtcTimestamp CREATED_AT =
            UtcTimestamp.parse("2026-08-17T05:00:00Z");
    private static final UtcTimestamp CHANGED_AT =
            UtcTimestamp.parse("2026-08-17T05:01:00Z");

    @Test
    void registersActiveLocalManagedRepositoryInExactWorkProjectScope() {
        Fixture fixture = Fixture.create();

        RepositoryBinding binding = fixture.binding();

        assertEquals(RepositoryKind.LOCAL_MANAGED, binding.kind());
        assertEquals(RepositoryBindingStatus.ACTIVE, binding.status());
        assertEquals("crewscope-java", binding.repositoryKey().value());
        assertEquals("main", binding.defaultBranch().value());
        assertEquals(fixture.organizationId, binding.scope().organizationId());
        assertEquals(fixture.team.team().id(), binding.scope().teamId());
        assertEquals(fixture.team.defaultWorkspace().id(), binding.scope().workspaceId());
        assertEquals(fixture.project.id(), binding.scope().workProjectId());
        assertEquals(0, binding.version());
        assertEquals(fixture.owner.id(), binding.audit().createdBy().orElseThrow());
        assertTrue(binding.acceptsNewTargets());
    }

    @Test
    void rejectsInactiveProjectAndActorOutsideTheProjectScope() {
        Fixture fixture = Fixture.create();
        WorkProject archived = fixture.project.archive(fixture.owner, CHANGED_AT);
        Principal outside = activeUser(OrganizationId.generate(), "Outside");
        Principal disabled = fixture.owner.transitionTo(PrincipalStatus.DISABLED, CHANGED_AT);

        DomainValidationException projectFailure = assertThrows(
                DomainValidationException.class,
                () -> fixture.register(archived, fixture.owner));
        DomainValidationException outsideFailure = assertThrows(
                DomainValidationException.class,
                () -> fixture.register(fixture.project, outside));
        DomainValidationException disabledFailure = assertThrows(
                DomainValidationException.class,
                () -> fixture.register(fixture.project, disabled));

        assertEquals(
                "repositoryBinding.workProjectId",
                projectFailure.error().details().get("field"));
        assertEquals(
                "repositoryBinding.createdByPrincipalId",
                outsideFailure.error().details().get("field"));
        assertEquals(
                "repositoryBinding.createdByPrincipalId",
                disabledFailure.error().details().get("field"));
    }

    @Test
    void repositoryKeyUsesTheFrozenManagedResolverFormat() {
        assertEquals("a", new RepositoryKey("a").value());
        assertEquals("a".repeat(63), new RepositoryKey("a".repeat(63)).value());

        for (String invalid : new String[] {
            "", "CrewScope", "-repo", "a".repeat(64), "../repo", "repo/path", "repo_name"
        }) {
            assertThrows(DomainValidationException.class, () -> new RepositoryKey(invalid));
        }
    }

    @Test
    void defaultBranchAcceptsShortHierarchyAndRejectsUnsafeGitRefs() {
        assertEquals("release/2026.08", new RepositoryBranchName("release/2026.08").value());

        for (String invalid : new String[] {
            "",
            "-main",
            "refs/heads/main",
            "/main",
            "main/",
            "feature//one",
            "feature/../main",
            "feature/.hidden",
            "feature/main.lock",
            "main@{1}",
            "main branch",
            "main\\other"
        }) {
            assertThrows(
                    DomainValidationException.class, () -> new RepositoryBranchName(invalid));
        }
    }

    @Test
    void disablesAndReactivatesWithMonotonicVersionAndAudit() {
        Fixture fixture = Fixture.create();
        RepositoryBinding binding = fixture.binding();

        RepositoryBinding disabled = binding.disable(0, fixture.owner, CHANGED_AT);
        RepositoryBinding active = disabled.activate(
                1, fixture.owner, UtcTimestamp.parse("2026-08-17T05:02:00Z"));

        assertEquals(RepositoryBindingStatus.DISABLED, disabled.status());
        assertFalse(disabled.acceptsNewTargets());
        assertEquals(1, disabled.version());
        assertEquals(CHANGED_AT, disabled.audit().updatedAt());
        assertEquals(RepositoryBindingStatus.ACTIVE, active.status());
        assertEquals(2, active.version());
        assertEquals(binding.repositoryKey(), active.repositoryKey());
        assertThrows(
                InvalidStateTransitionException.class,
                () -> disabled.disable(1, fixture.owner, CHANGED_AT));
        assertThrows(
                InvalidStateTransitionException.class,
                () -> active.activate(
                        2, fixture.owner, UtcTimestamp.parse("2026-08-17T05:03:00Z")));
    }

    @Test
    void changesOnlyTheDefaultBranchAndChecksExpectedVersion() {
        Fixture fixture = Fixture.create();
        RepositoryBinding binding = fixture.binding();

        RepositoryBinding changed = binding.changeDefaultBranch(
                new RepositoryBranchName("develop"), 0, fixture.owner, CHANGED_AT);

        assertEquals("develop", changed.defaultBranch().value());
        assertEquals(binding.repositoryKey(), changed.repositoryKey());
        assertEquals(binding.scope(), changed.scope());
        assertEquals(1, changed.version());
        assertThrows(
                OptimisticLockConflictException.class,
                () -> changed.disable(0, fixture.owner, CHANGED_AT));
        assertThrows(
                DomainValidationException.class,
                () -> changed.changeDefaultBranch(
                        changed.defaultBranch(), 1, fixture.owner, CHANGED_AT));
    }

    @Test
    void definesAStableConflictForDuplicateKeysInsideOneWorkProject() {
        Fixture fixture = Fixture.create();

        RepositoryBindingKeyConflictException failure =
                new RepositoryBindingKeyConflictException(
                        fixture.project.id(), new RepositoryKey("crewscope-java"));

        assertEquals(
                DomainErrorCode.REPOSITORY_BINDING_KEY_CONFLICT, failure.error().code());
        assertEquals(
                fixture.project.id().toString(),
                failure.error().details().get("workProjectId"));
        assertEquals("crewscope-java", failure.error().details().get("repositoryKey"));
    }

    @Test
    void aggregatePublicSurfaceNeverCarriesAHostFilesystemPath() {
        assertTrue(Arrays.stream(RepositoryBinding.class.getDeclaredFields())
                .map(Field::getType)
                .noneMatch(Path.class::isAssignableFrom));
        assertTrue(Arrays.stream(RepositoryBinding.class.getMethods())
                .map(Method::getName)
                .noneMatch(name -> name.toLowerCase().contains("path")));
        assertTrue(Arrays.stream(RepositoryBinding.class.getMethods())
                .flatMap(method -> Arrays.stream(method.getParameterTypes()))
                .noneMatch(Path.class::isAssignableFrom));
    }

    private static Principal activeUser(OrganizationId organizationId, String name) {
        return Principal.create(
                PrincipalId.generate(),
                PrincipalScope.organization(organizationId),
                PrincipalType.USER,
                Optional.empty(),
                name,
                Optional.empty(),
                PrincipalVisibility.ORGANIZATION,
                CREATED_AT);
    }

    private record Fixture(
            OrganizationId organizationId,
            Principal owner,
            TeamInitialization team,
            WorkProject project) {

        private static Fixture create() {
            OrganizationId organizationId = OrganizationId.generate();
            Principal owner = activeUser(organizationId, "Owner");
            TeamInitialization team = TeamInitialization.create(owner, "CrewScope", CREATED_AT);
            WorkProject project = WorkProject.create(
                    WorkProjectId.generate(),
                    new WorkProjectKey("COD"),
                    "Coding",
                    team.team(),
                    team.defaultWorkspace(),
                    owner,
                    CREATED_AT);
            return new Fixture(organizationId, owner, team, project);
        }

        private RepositoryBinding binding() {
            return register(project, owner);
        }

        private RepositoryBinding register(WorkProject target, Principal actor) {
            return RepositoryBinding.registerLocalManaged(
                    RepositoryBindingId.generate(),
                    target,
                    new RepositoryKey("crewscope-java"),
                    new RepositoryBranchName("main"),
                    actor,
                    CREATED_AT);
        }
    }
}
