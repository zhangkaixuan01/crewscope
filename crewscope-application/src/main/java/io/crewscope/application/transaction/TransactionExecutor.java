package io.crewscope.application.transaction;

import java.util.function.Supplier;

/** Application Port for executing one unit of work in a required database transaction. */
public interface TransactionExecutor {

    /** Joins an existing transaction or opens one and commits only after the operation succeeds. */
    <T> T required(Supplier<T> operation);
}
