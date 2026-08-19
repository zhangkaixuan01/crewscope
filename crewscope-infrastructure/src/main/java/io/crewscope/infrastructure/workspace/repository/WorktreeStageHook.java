package io.crewscope.infrastructure.workspace.repository;

/** Test hook kept outside production Spring wiring. */
@FunctionalInterface
interface WorktreeStageHook {

    WorktreeStageHook NONE = stage -> {};

    void reached(WorktreeProvisionStage stage);
}
