package io.crewscope.application.coding;

/** Path-free input used to bind one managed Repository Key to a WorkProject. */
public record CreateRepositoryBindingCommand(String repositoryKey, String defaultBranch) {}
