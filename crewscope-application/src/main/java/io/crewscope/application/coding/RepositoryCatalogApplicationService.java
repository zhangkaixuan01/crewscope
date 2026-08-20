package io.crewscope.application.coding;

import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Authorizes and returns the managed Repository Catalog for one WorkProject settings page. */
public final class RepositoryCatalogApplicationService {

    private final RepositoryBindingAccessPolicy accessPolicy;
    private final RepositoryCatalogPort catalogPort;
    private final TimeProvider timeProvider;

    public RepositoryCatalogApplicationService(
            RepositoryBindingAccessPolicy accessPolicy,
            RepositoryCatalogPort catalogPort,
            TimeProvider timeProvider) {
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
        this.catalogPort = Objects.requireNonNull(catalogPort, "catalogPort");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
    }

    public List<RepositoryCatalogEntry> list(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId projectId) {
        accessPolicy.requireAdministrator(
                context, organizationId, teamId, projectId, timeProvider.now());
        return catalogPort.list().stream()
                .sorted(Comparator.comparing(entry -> entry.repositoryKey().value()))
                .toList();
    }
}
