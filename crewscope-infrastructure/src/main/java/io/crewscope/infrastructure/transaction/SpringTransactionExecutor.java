package io.crewscope.infrastructure.transaction;

import io.crewscope.application.transaction.TransactionExecutor;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Spring implementation of the application REQUIRED transaction boundary. */
@Component
public class SpringTransactionExecutor implements TransactionExecutor {

    private final TransactionTemplate transactionTemplate;

    public SpringTransactionExecutor(PlatformTransactionManager transactionManager) {
        this.transactionTemplate = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
        this.transactionTemplate.setName("crewscope-required-unit-of-work");
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    }

    @Override
    public <T> T required(Supplier<T> operation) {
        Supplier<T> requiredOperation = Objects.requireNonNull(operation, "operation");
        return transactionTemplate.execute(status -> requiredOperation.get());
    }
}
