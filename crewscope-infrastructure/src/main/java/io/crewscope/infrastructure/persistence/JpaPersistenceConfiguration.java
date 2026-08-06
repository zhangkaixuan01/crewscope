package io.crewscope.infrastructure.persistence;

import io.crewscope.infrastructure.persistence.workitem.WorkItemEntity;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;

/** Registers infrastructure JPA entities outside the server application's package tree. */
@Configuration(proxyBeanMethods = false)
@EntityScan(basePackageClasses = WorkItemEntity.class)
public class JpaPersistenceConfiguration {}
