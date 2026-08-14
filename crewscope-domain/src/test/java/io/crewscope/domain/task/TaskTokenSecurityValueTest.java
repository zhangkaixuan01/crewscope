package io.crewscope.domain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.shared.error.DomainValidationException;
import org.junit.jupiter.api.Test;

class TaskTokenSecurityValueTest {

    @Test
    void hashesJtiAndRedactsEveryTaskTokenStringBoundary() {
        TaskCredentialGrantDomainFixture fixture = new TaskCredentialGrantDomainFixture();
        TaskTokenJti jti = new TaskTokenJti(TaskCredentialGrantDomainFixture.JTI_VALUE);
        TaskCredentialIssuance issuance = fixture.issue();

        assertEquals(64, jti.hash().value().length());
        assertNotEquals(TaskCredentialGrantDomainFixture.JTI_VALUE, jti.hash().value());
        String joined = String.join("\n",
                jti.toString(),
                jti.hash().toString(),
                issuance.claims().toString(),
                issuance.grant().scope().toString(),
                issuance.grant().toString(),
                issuance.toString(),
                fixture.providerRequest().toString(),
                fixture.providerAccess().toString());
        assertFalse(joined.contains(TaskCredentialGrantDomainFixture.JTI_VALUE));
        assertFalse(joined.contains(jti.hash().value()));
        assertFalse(joined.contains(TaskCredentialGrantDomainFixture.RESOURCE));
        assertFalse(joined.contains("repository.read"));
        assertTrue(joined.contains("REDACTED"));
    }

    @Test
    void validatesJtiEntropyFormatAndHashShape() {
        assertThrows(DomainValidationException.class, () -> new TaskTokenJti("too-short"));
        assertThrows(
                DomainValidationException.class,
                () -> new TaskTokenJti("!".repeat(43)));
        assertThrows(DomainValidationException.class, () -> new TaskTokenJtiHash("A".repeat(64)));
    }

    @Test
    void sameJtiProducesSameUniquePersistenceKey() {
        TaskTokenJti first = new TaskTokenJti(TaskCredentialGrantDomainFixture.JTI_VALUE);
        TaskTokenJti duplicate = new TaskTokenJti(TaskCredentialGrantDomainFixture.JTI_VALUE);
        TaskTokenJti other = new TaskTokenJti(TaskCredentialGrantDomainFixture.OTHER_JTI_VALUE);

        assertEquals(first.hash(), duplicate.hash());
        assertNotEquals(first.hash(), other.hash());
    }
}
