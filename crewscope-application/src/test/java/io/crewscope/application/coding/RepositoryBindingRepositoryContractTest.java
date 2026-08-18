package io.crewscope.application.coding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.crewscope.domain.coding.RepositoryBinding;
import io.crewscope.domain.coding.RepositoryBindingId;
import io.crewscope.domain.coding.RepositoryBindingKeyConflictException;
import io.crewscope.domain.coding.RepositoryBindingStatus;
import io.crewscope.domain.coding.RepositoryBranchName;
import io.crewscope.domain.coding.RepositoryKey;
import io.crewscope.domain.coding.RepositoryKind;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.domain.coding.RepositoryBindingScope;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Executable scope and uniqueness contract for the future PostgreSQL repository adapter. */
class RepositoryBindingRepositoryContractTest {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-17T05:00:00Z");
    private static final OrganizationId ORGANIZATION_ID = OrganizationId.generate();
    private static final TeamId TEAM_ID = TeamId.generate();
    private static final WorkspaceId WORKSPACE_ID = WorkspaceId.generate();
    private static final WorkProjectId PROJECT_ID = WorkProjectId.generate();

    @Test
    void scopesQueriesAndUniquenessToTheCompleteWorkProjectCoordinates() {
        InMemoryRepository repository = new InMemoryRepository();
        RepositoryBinding first = binding(PROJECT_ID, "crewscope-java");
        WorkProjectId otherProjectId = WorkProjectId.generate();

        repository.create(first);
        repository.create(binding(otherProjectId, "crewscope-java"));

        assertThrows(
                RepositoryBindingKeyConflictException.class,
                () -> repository.create(binding(PROJECT_ID, "crewscope-java")));
        assertEquals(
                first.id(),
                repository
                        .findByKey(
                                ORGANIZATION_ID,
                                TEAM_ID,
                                PROJECT_ID,
                                new RepositoryKey("crewscope-java"))
                        .orElseThrow()
                        .id());
        assertEquals(
                1,
                repository
                        .findByWorkProject(ORGANIZATION_ID, TEAM_ID, otherProjectId)
                        .size());
        assertEquals(
                Optional.empty(),
                repository.findById(
                        OrganizationId.generate(), TEAM_ID, PROJECT_ID, first.id()));
    }

    private static RepositoryBinding binding(WorkProjectId projectId, String key) {
        PrincipalId actorId = PrincipalId.generate();
        return RepositoryBinding.reconstitute(
                RepositoryBindingId.generate(),
                new RepositoryBindingScope(
                        ORGANIZATION_ID, TEAM_ID, WORKSPACE_ID, projectId),
                RepositoryKind.LOCAL_MANAGED,
                new RepositoryKey(key),
                new RepositoryBranchName("main"),
                RepositoryBindingStatus.ACTIVE,
                0,
                AuditMetadata.createdBy(actorId, NOW));
    }

    private static final class InMemoryRepository implements RepositoryBindingRepository {

        private final List<RepositoryBinding> values = new ArrayList<>();

        @Override
        public RepositoryBinding create(RepositoryBinding binding) {
            findByKey(
                            binding.scope().organizationId(),
                            binding.scope().teamId(),
                            binding.scope().workProjectId(),
                            binding.repositoryKey())
                    .ifPresent(ignored -> {
                        throw new RepositoryBindingKeyConflictException(
                                binding.scope().workProjectId(), binding.repositoryKey());
                    });
            values.add(binding);
            return binding;
        }

        @Override
        public RepositoryBinding update(RepositoryBinding binding) {
            throw new UnsupportedOperationException("not needed by this contract scenario");
        }

        @Override
        public Optional<RepositoryBinding> findById(
                OrganizationId organizationId,
                TeamId teamId,
                WorkProjectId workProjectId,
                RepositoryBindingId bindingId) {
            return values.stream()
                    .filter(value -> matches(value, organizationId, teamId, workProjectId))
                    .filter(value -> value.id().equals(bindingId))
                    .findFirst();
        }

        @Override
        public Optional<RepositoryBinding> findByKey(
                OrganizationId organizationId,
                TeamId teamId,
                WorkProjectId workProjectId,
                RepositoryKey repositoryKey) {
            return values.stream()
                    .filter(value -> matches(value, organizationId, teamId, workProjectId))
                    .filter(value -> value.repositoryKey().equals(repositoryKey))
                    .findFirst();
        }

        @Override
        public List<RepositoryBinding> findByWorkProject(
                OrganizationId organizationId,
                TeamId teamId,
                WorkProjectId workProjectId) {
            return values.stream()
                    .filter(value -> matches(value, organizationId, teamId, workProjectId))
                    .toList();
        }

        private static boolean matches(
                RepositoryBinding value,
                OrganizationId organizationId,
                TeamId teamId,
                WorkProjectId workProjectId) {
            return value.scope().organizationId().equals(organizationId)
                    && value.scope().teamId().equals(teamId)
                    && value.scope().workProjectId().equals(workProjectId);
        }
    }
}
