package io.crewscope.application.quota;

/**
 * The token ledger row changed underneath the reservation attempt (lost CAS or an impossible
 * read state). Thrown to fail the admission transaction closed — never swallowed into a
 * policy denial.
 */
public final class QuotaLedgerContextException extends RuntimeException {

    public QuotaLedgerContextException(String message) {
        super(message);
    }
}
