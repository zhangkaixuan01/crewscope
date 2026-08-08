package io.crewscope.infrastructure.persistence;

import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;

/** Registers infrastructure JPA entities outside the server application's package tree. */
@Configuration(proxyBeanMethods = false)
@EntityScan(basePackageClasses = JpaPersistenceConfiguration.class)
public class JpaPersistenceConfiguration {}
