package io.crewscope.domain.organization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import org.junit.jupiter.api.Test;

/** ADR-035 invariants of the materialized aggregate: name bounds, status vocabulary, version. */
class OrganizationTest {

    private static final UtcTimestamp CREATED_AT = UtcTimestamp.parse("2026-10-12T08:00:00Z");
    private static final UtcTimestamp UPDATED_AT = UtcTimestamp.parse("2026-10-12T09:00:00Z");

    @Test
    void reconstitutesWithTheSixColumnStateSurface() {
        OrganizationId id = OrganizationId.generate();
        Organization organization =
                Organization.reconstitute(
                        id, "  CrewScope Organization  ", OrganizationStatus.ACTIVE,
                        3L, CREATED_AT, UPDATED_AT);
        assertEquals(id, organization.id());
        assertEquals("CrewScope Organization", organization.name());
        assertEquals(OrganizationStatus.ACTIVE, organization.status());
        assertEquals(3L, organization.version());
        assertEquals(CREATED_AT, organization.createdAt());
        assertEquals(UPDATED_AT, organization.updatedAt());
    }

    @Test
    void rejectsBlankName() {
        DomainValidationException failure =
                assertThrows(
                        DomainValidationException.class,
                        () -> Organization.reconstitute(
                                OrganizationId.generate(), "   ", OrganizationStatus.ACTIVE,
                                0L, CREATED_AT, UPDATED_AT));
        assertTrue(failure.getMessage().contains("organization.name"));
    }

    @Test
    void rejectsNameLongerThanTheV1Column() {
        String tooLong = "x".repeat(Organization.NAME_MAX_LENGTH + 1);
        DomainValidationException failure =
                assertThrows(
                        DomainValidationException.class,
                        () -> Organization.reconstitute(
                                OrganizationId.generate(), tooLong, OrganizationStatus.ACTIVE,
                                0L, CREATED_AT, UPDATED_AT));
        assertTrue(failure.getMessage().contains("organization.name"));
    }

    @Test
    void acceptsNameExactlyAtTheColumnLimit() {
        String atLimit = "x".repeat(Organization.NAME_MAX_LENGTH);
        Organization organization =
                Organization.reconstitute(
                        OrganizationId.generate(), atLimit, OrganizationStatus.ACTIVE,
                        0L, CREATED_AT, UPDATED_AT);
        assertEquals(atLimit, organization.name());
    }

    @Test
    void rejectsNegativeVersion() {
        DomainValidationException failure =
                assertThrows(
                        DomainValidationException.class,
                        () -> Organization.reconstitute(
                                OrganizationId.generate(), "CrewScope Organization",
                                OrganizationStatus.ACTIVE, -1L, CREATED_AT, UPDATED_AT));
        assertTrue(failure.getMessage().contains("organization.version"));
    }

    @Test
    void statusParserAcceptsOnlyTheObservedVocabulary() {
        assertEquals(OrganizationStatus.ACTIVE, OrganizationStatus.from("ACTIVE"));
        assertThrows(DomainValidationException.class, () -> OrganizationStatus.from("ARCHIVED"));
        assertThrows(DomainValidationException.class, () -> OrganizationStatus.from(null));
    }
}
