package io.crewscope.infrastructure.persistence.modelagent;

import io.crewscope.application.agent.AgentTemplateCatalogInitializer;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Repairs the platform-owned Agent catalog after a business-data reset or an old deployment. */
@Component
public final class AgentTemplateCatalogStartupSeeder {

    private final JdbcTemplate jdbc;
    private final AgentTemplateCatalogInitializer initializer;

    public AgentTemplateCatalogStartupSeeder(
            JdbcTemplate jdbc, AgentTemplateCatalogInitializer initializer) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.initializer = Objects.requireNonNull(initializer, "initializer");
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seedExistingOrganizations() {
        List<OrganizationOwner> owners = jdbc.query(
                """
                SELECT organization.id, principal.id
                  FROM crewscope.organization organization
                  JOIN LATERAL (
                    SELECT id
                      FROM crewscope.principal
                     WHERE organization_id = organization.id
                       AND status = 'ACTIVE'
                       AND principal_type IN ('USER', 'SERVICE')
                     ORDER BY CASE principal_type WHEN 'USER' THEN 0 ELSE 1 END, created_at, id
                     LIMIT 1
                  ) principal ON TRUE
                 WHERE organization.status = 'ACTIVE'
                """,
                (row, ignored) -> new OrganizationOwner(
                        OrganizationId.from(row.getString(1)), PrincipalId.from(row.getString(2))));
        UtcTimestamp now = UtcTimestamp.from(Instant.now());
        owners.forEach(owner -> initializer.initialize(owner.organization(), owner.actor(), now));
    }

    private record OrganizationOwner(OrganizationId organization, PrincipalId actor) {}
}
