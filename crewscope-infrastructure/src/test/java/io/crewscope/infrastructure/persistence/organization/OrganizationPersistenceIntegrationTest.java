package io.crewscope.infrastructure.persistence.organization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.organization.OrganizationRepository;
import io.crewscope.domain.organization.Organization;
import io.crewscope.domain.organization.OrganizationStatus;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest;
import io.crewscope.infrastructure.testsupport.SharedJpaTestApplication;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Reads the V1 {@code crewscope.organization} table through the materialized aggregate (ADR-035)
 * against migrated PostgreSQL: the seeder-shaped row round-trips all six columns, unknown ids stay
 * empty, and a status outside the observed vocabulary fails fast instead of leaking through.
 */
@SpringBootTest(
        classes = SharedJpaTestApplication.class,
        properties = {
            "spring.flyway.schemas=crewscope",
            "spring.flyway.default-schema=crewscope",
            "spring.flyway.create-schemas=true",
            "spring.jpa.hibernate.ddl-auto=validate",
            "spring.jpa.properties.hibernate.default_schema=crewscope",
            "spring.jpa.open-in-view=false"
        })
class OrganizationPersistenceIntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    @Autowired private OrganizationRepository organizationRepository;

    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void readsSeederShapedRowThroughTheAggregate() {
        OrganizationId id = OrganizationId.generate();
        jdbcTemplate.update(
                "INSERT INTO crewscope.organization (id, name, status, version, created_at,"
                        + " updated_at) VALUES (?, ?, 'ACTIVE', ?, NOW(), NOW())",
                id.value(), "D01 Round-Trip Org", 5L);

        Optional<Organization> found = organizationRepository.findById(id);

        assertTrue(found.isPresent());
        Organization organization = found.orElseThrow();
        assertEquals(id, organization.id());
        assertEquals("D01 Round-Trip Org", organization.name());
        assertEquals(OrganizationStatus.ACTIVE, organization.status());
        assertEquals(5L, organization.version());
        assertTrue(organization.createdAt() != null && organization.updatedAt() != null);
    }

    @Test
    void unknownIdStaysEmpty() {
        assertTrue(organizationRepository.findById(OrganizationId.generate()).isEmpty());
    }

    @Test
    void unknownStatusFailsFastRatherThanMappingThrough() {
        OrganizationId id = new OrganizationId(UUID.randomUUID());
        jdbcTemplate.update(
                "INSERT INTO crewscope.organization (id, name, status, version, created_at,"
                        + " updated_at) VALUES (?, ?, ?, 0, NOW(), NOW())",
                id.value(), "Dirty Status Org", "ARCHIVED");

        DomainValidationException failure =
                assertThrows(
                        DomainValidationException.class,
                        () -> organizationRepository.findById(id));
        assertTrue(failure.getMessage().contains("organization.status"));
    }
}
