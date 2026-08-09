package io.crewscope.application.provider;

/** Trusted precedence level at which Provider Binding resolution terminated. */
public enum ProviderBindingResolutionLevel {
    ACTION_EXPLICIT,
    TASK_EXPLICIT,
    WORK_PROJECT,
    WORKSPACE,
    ORGANIZATION_DEFAULT,
    NONE
}
