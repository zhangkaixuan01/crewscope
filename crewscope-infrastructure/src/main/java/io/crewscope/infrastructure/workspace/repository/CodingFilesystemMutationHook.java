package io.crewscope.infrastructure.workspace.repository;

/** Deterministic seam used to prove final path revalidation immediately before mutation. */
@FunctionalInterface
interface CodingFilesystemMutationHook {

    CodingFilesystemMutationHook NONE = (operation, paths) -> {};

    void beforeFinalValidation(String operation, java.util.List<String> paths);
}
