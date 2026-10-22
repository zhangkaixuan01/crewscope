package io.crewscope.infrastructure.persistence.taskruntime;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Same-package bridge so the shared test applications can import this package's
 * package-private collaborators (direct references are only legal from inside the package).
 */
@Configuration
@Import({ResolvedAgentExecutionConfigurationJsonCodec.class, TaskRuntimeJpaSupport.class})
public class TaskRuntimePackageConfiguration {}
