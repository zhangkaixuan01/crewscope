package io.crewscope.infrastructure.testsupport;

import io.crewscope.infrastructure.event.JdbcOutboxClaimStore;
import io.crewscope.infrastructure.persistence.collaboration.JdbcLarkCollaborationRepositoryAdapter;
import io.crewscope.infrastructure.persistence.taskruntime.JdbcTaskExecutionQueueRepositoryAdapter;
import io.crewscope.infrastructure.persistence.taskruntime.TaskRuntimePersistenceMapper;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Import;

/**
 * One shared boot configuration for the flyway-only JDBC integration tests, so every class
 * joins the same cached Spring TestContext instead of cold-starting a private application.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@Import({
    JdbcOutboxClaimStore.class,
    JdbcLarkCollaborationRepositoryAdapter.class,
    TaskRuntimePersistenceMapper.class,
    JdbcTaskExecutionQueueRepositoryAdapter.class
})
public class SharedJdbcTestApplication {}
